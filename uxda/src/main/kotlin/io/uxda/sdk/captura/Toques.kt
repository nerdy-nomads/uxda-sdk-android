package io.uxda.sdk.captura

import android.os.Handler
import android.os.Looper
import android.view.View
import io.uxda.sdk.Seguranca
import io.uxda.sdk.Tipos

/**
 * Tentativas de interação: o que a pessoa tentou e não deu. Cartão 4.1,
 * RF-GRA-01 a RF-GRA-05 e RF-GRA-08.
 *
 * O documento chama a estes os sinais mais subvalorizados que existem, e a razão
 * é simples: uma zona onde muita gente toca e nada acontece é uma falha de desenho
 * documentada com precisão, e nenhuma ferramenta de funis a revela, porque um funil
 * só vê os passos que **aconteceram**.
 *
 * Em Android o caso do elemento desativado é mais fácil do que na web: a vista
 * está lá na árvore, com `isEnabled` a `false`, e vê-se. Na web o browser nem
 * despacha o evento.
 */
internal class Toques(
    private val emitir: (String, String?, Long?, Map<String, Any>?) -> Unit,
    private val agora: () -> Long,
    private val detalhado: () -> Boolean,
) {
    /** Uma rajada acaba quando passa este tempo sem outro toque no mesmo sítio. */
    private val janelaDeRajadaMs = 1200L

    private val mao = Handler(Looper.getMainLooper())
    private var ecraMostradoEm = agora()
    private var jaInteragiu = false

    private var rajadaChave = ""
    private var rajadaToques = 0
    private var rajadaPrimeiroEm = 0L
    private var rajadaUltimoEm = 0L
    private val fecharMaisTarde = Runnable { Seguranca.executar("toques.rajada") { fecharRajada() } }

    /**
     * A zona é uma grelha grosseira sobre o ecrã, e não as coordenadas.
     *
     * Serve para agrupar: "muita gente toca no canto inferior direito do ecrã de
     * pagamento" é acionável, e "alguém tocou no pixel 412,908" não é. E é o que
     * deixa juntar telemóveis e computadores no mesmo mapa, que com pixels não dava.
     */
    fun zonaDe(x: Float, y: Float, largura: Int, altura: Int): String {
        val col = ((x / maxOf(1, largura)) * 6).toInt().coerceIn(0, 5)
        val lin = ((y / maxOf(1, altura)) * 10).toInt().coerceIn(0, 9)
        return "${col}x$lin"
    }

    private fun porCento(v: Float, total: Int): Int =
        ((v / maxOf(1, total)) * 100).toInt().coerceIn(0, 100)

    private fun coordenadas(x: Float, y: Float, largura: Int, altura: Int): Map<String, Any> =
        if (detalhado()) mapOf("toque_x" to porCento(x, largura), "toque_y" to porCento(y, altura))
        else emptyMap()

    /** Um toque que não encontrou nada acionável debaixo do dedo. */
    fun semAlvo(x: Float, y: Float, largura: Int, altura: Int) {
        emitir(
            Tipos.TOQUE_SEM_ALVO, null, null,
            coordenadas(x, y, largura, altura) + mapOf("zona" to zonaDe(x, y, largura, altura)),
        )
    }

    /** Um toque num botão desativado, e ninguém lhe disse porquê. */
    fun desativado(chave: String?, x: Float, y: Float, largura: Int, altura: Int) {
        emitir(
            Tipos.TOQUE_DESATIVADO, chave, null,
            coordenadas(x, y, largura, altura) + mapOf(
                "zona" to zonaDe(x, y, largura, altura),
                "alvo_desativado" to true,
            ),
        )
    }

    /** Tocou enquanto a aplicação estava ocupada, sem o perceber. */
    fun emCarregamento(chave: String?) {
        emitir(Tipos.TOQUE_EM_CARREGAMENTO, chave, null, mapOf("em_carregamento" to true))
    }

    /**
     * Insistir no mesmo sítio quer dizer que o sistema não respondeu à vista dela.
     *
     * Sai **um** evento no fim da rajada, com a contagem e o intervalo médio, e não
     * um por toque: um sinal de frustração que multiplica o volume por três é um
     * sinal que ninguém vai deixar ligado.
     */
    fun anotar(chave: String) {
        val t = agora()
        if (chave != rajadaChave || t - rajadaUltimoEm > janelaDeRajadaMs) {
            fecharRajada()
            rajadaChave = chave
            rajadaToques = 1
            rajadaPrimeiroEm = t
        } else {
            rajadaToques++
        }
        rajadaUltimoEm = t
        mao.removeCallbacks(fecharMaisTarde)
        mao.postDelayed(fecharMaisTarde, janelaDeRajadaMs)
    }

    fun fecharRajada() {
        if (rajadaToques >= 2 && rajadaChave.isNotEmpty()) {
            val total = (rajadaUltimoEm - rajadaPrimeiroEm).coerceAtLeast(0)
            emitir(
                Tipos.TOQUE_REPETIDO, rajadaChave, total,
                mapOf(
                    "repeticoes" to rajadaToques,
                    "intervalo_ms" to (total / maxOf(1, rajadaToques - 1)),
                ),
            )
        }
        rajadaChave = ""
        rajadaToques = 0
        mao.removeCallbacks(fecharMaisTarde)
    }

    /** O tempo até à primeira interação conta-se **por ecrã**, e não por sessão. */
    fun primeiraInteracao() {
        if (jaInteragiu) return
        jaInteragiu = true
        emitir(Tipos.PRIMEIRA_INTERACAO, null, (agora() - ecraMostradoEm).coerceAtLeast(0), null)
    }

    fun ecraNovo() {
        fecharRajada()
        ecraMostradoEm = agora()
        jaInteragiu = false
    }

    /** Uma vista desativada, ou marcada como tal para quem lê o ecrã. */
    fun estaDesativado(v: View?): Boolean = v != null && !v.isEnabled
}

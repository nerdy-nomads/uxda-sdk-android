package io.uxda.sdk.captura

import android.os.Handler
import android.os.Looper
import android.view.View
import io.uxda.sdk.Seguranca
import io.uxda.sdk.Tipos

/**
 * Tentativas de interação: o que a pessoa tentou e não deu. Cartões 4.1 e 9.1,
 * RF-GRA-01 a RF-GRA-05, RF-GRA-08 e RF-IND-01 a RF-IND-03.
 *
 * O documento chama a estes os sinais mais subvalorizados que existem, e a razão
 * é simples: uma zona onde muita gente toca e nada acontece é uma falha de desenho
 * documentada com precisão, e nenhuma ferramenta de funis a revela, porque um funil
 * só vê os passos que **aconteceram**.
 *
 * Em Android o caso do elemento desativado é mais fácil do que na web: a vista
 * está lá na árvore, com `isEnabled` a `false`, e vê-se. Na web o browser nem
 * despacha o evento.
 *
 * # O que o cartão 9.1 acrescentou, e é igual ao da web
 *
 * Três percentagens sobre o mesmo toque, porque respondem a perguntas diferentes:
 * `toque_x` é do ecrã e desenha o mapa de calor; `alvo_x` é da caixa da vista e
 * diz se a pessoa acertou no meio do botão ou na beira dele; e `alvo_caixa` é a
 * caixa da vista no ecrã, de onde sai o esquema do cartão 9.2 **sem nunca
 * fotografar nada**.
 *
 * A paridade com a web não é intenção: é medida pelo `./scripts/paridade.sh`, que
 * compara a mesma tarefa nas duas plataformas evento a evento.
 */
internal class Toques(
    private val emitir: (String, String?, Long?, Map<String, Any>?) -> Unit,
    private val agora: () -> Long,
    private val detalhado: () -> Boolean,
    /**
     * O rastreio individual do projeto (cartão 9.5, `RF-IND-09`).
     *
     * **É uma segunda condição, e não a mesma que o nível detalhado.** O nível diz
     * quanta granularidade se capta; isto diz se é legítimo seguir uma pessoa.
     */
    private val individual: () -> Boolean = { false },
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

    /** As duas condições, e não uma: ver o comentário do construtor. */
    private fun segue(): Boolean = detalhado() && individual()

    /** A ordem desta interação **dentro do ecrã**, e não da sessão (RF-IND-01). */
    private var ordem = 0

    private fun coordenadas(x: Float, y: Float, largura: Int, altura: Int): Map<String, Any> {
        ordem++
        if (!segue()) return emptyMap()
        return mapOf(
            "toque_x" to porCento(x, largura),
            "toque_y" to porCento(y, altura),
            "visor_largura" to largura,
            "visor_altura" to altura,
            "ordem_na_sequencia" to ordem,
        )
    }

    /**
     * A caixa da vista no ecrã e o ponto dentro dela, em percentagem.
     *
     * Devolve vazio quando a vista não sabe onde está (largura ou altura a zero,
     * que acontece numa vista ainda por desenhar): **melhor não desenhar do que
     * desenhar no sítio errado**.
     */
    private fun doAlvo(v: View?, x: Float, y: Float, largura: Int, altura: Int): Map<String, Any> {
        if (!segue() || v == null || v.width <= 0 || v.height <= 0) return emptyMap()
        val pos = IntArray(2)
        Seguranca.executar("toques.caixa") { v.getLocationOnScreen(pos) }
        val esq = pos[0].toFloat()
        val topo = pos[1].toFloat()
        return mapOf(
            "alvo_x" to porCento(x - esq, v.width),
            "alvo_y" to porCento(y - topo, v.height),
            "alvo_caixa" to listOf(
                porCento(esq, largura), porCento(topo, altura),
                porCento(v.width.toFloat(), largura), porCento(v.height.toFloat(), altura),
            ).joinToString(","),
        )
    }

    /**
     * A posição de um toque **que funcionou**, para o evento de `toque` a levar.
     * Cartão 9.1, RF-IND-02.
     *
     * # Porque é que isto existe, e é o defeito que corrigiu
     *
     * Os três casos em que nada acontece emitem-se aqui; o toque normal é emitido
     * pela `Captura`, que é onde a identidade da vista se resolve. O resultado era
     * que as coordenadas saíam nos toques mortos e **não saíam nos toques
     * normais**, e o mapa de calor do cartão 9.2 ficava a desenhar só as falhas,
     * que é o oposto de um mapa de calor.
     *
     * Deu-se por isso do lado da web, a correr a loja de ensaio no browser, e a
     * paridade obrigou a corrigir os dois: o `./scripts/paridade.sh` compara a
     * mesma tarefa nas duas plataformas evento a evento.
     */
    fun posicaoDoToque(v: View?, x: Float, y: Float, largura: Int, altura: Int): Map<String, Any> =
        coordenadas(x, y, largura, altura) + doAlvo(v, x, y, largura, altura) +
            mapOf("zona" to zonaDe(x, y, largura, altura))

    /** Um toque que não encontrou nada acionável debaixo do dedo. */
    fun semAlvo(x: Float, y: Float, largura: Int, altura: Int) {
        emitir(
            Tipos.TOQUE_SEM_ALVO, null, null,
            coordenadas(x, y, largura, altura) + mapOf("zona" to zonaDe(x, y, largura, altura)),
        )
    }

    /** Um toque num botão desativado, e ninguém lhe disse porquê. */
    fun desativado(chave: String?, vista: View?, x: Float, y: Float, largura: Int, altura: Int) {
        emitir(
            Tipos.TOQUE_DESATIVADO, chave, null,
            coordenadas(x, y, largura, altura) + doAlvo(vista, x, y, largura, altura) + mapOf(
                "zona" to zonaDe(x, y, largura, altura),
                "alvo_desativado" to true,
            ),
        )
    }

    /** Tocou enquanto a aplicação estava ocupada, sem o perceber. */
    fun emCarregamento(chave: String?, vista: View?, x: Float, y: Float, largura: Int, altura: Int) {
        emCarregamento(chave, posicaoDoToque(vista, x, y, largura, altura))
    }

    /**
     * A mesma coisa com a posição já calculada.
     *
     * **Existe para a posição de um gesto se calcular uma vez só.** O
     * `coordenadas` avança o contador da ordem da interação, e um toque que
     * emitisse o `em_carregamento` e o `toque` com duas chamadas gastava dois
     * números de ordem no mesmo gesto: a sequência do `RF-IND-01` passava a ter
     * buracos, e eles aparecem precisamente nos ecrãs lentos, que são os que
     * alguém vai lá ver.
     */
    fun emCarregamento(chave: String?, onde: Map<String, Any>) {
        emitir(
            Tipos.TOQUE_EM_CARREGAMENTO, chave, null,
            onde + mapOf("em_carregamento" to true),
        )
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
        ordem = 0
    }

    /** Uma vista desativada, ou marcada como tal para quem lê o ecrã. */
    fun estaDesativado(v: View?): Boolean = v != null && !v.isEnabled
}

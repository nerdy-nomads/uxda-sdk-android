package io.uxea.sdk.captura

import android.view.View
import android.view.ViewTreeObserver
import io.uxea.sdk.Seguranca
import io.uxea.sdk.Tipos

/**
 * Até onde a pessoa desceu no ecrã. Cartão 9.1, RF-IND-05.
 *
 * # A pergunta que isto responde, e que nenhum outro sinal responde
 *
 * *O que está no fundo do ecrã chega a ser visto?* Um botão de continuar abaixo da
 * dobra, um aviso importante no rodapé, um terceiro plano de preços que 80% das
 * pessoas nunca vê: são todos invisíveis para um funil, porque **não acontece
 * nada** quando alguém não desce.
 *
 * É o mesmo raciocínio do toque sem efeito: o que interessa é a ausência, e a
 * ausência não produz eventos por si.
 *
 * # Um evento por ecrã, e não um por deslocamento
 *
 * Um `onScrollChanged` dispara a cada fotograma de um arrasto: capturar cada um
 * multiplicava o volume por cem para dizer a mesma coisa. Guarda-se o **máximo**, e
 * emite-se quando o ecrã acaba, que é o único momento em que ele é definitivo.
 *
 * # Como se mede em Android, e onde isto difere da web
 *
 * Na web a página tem uma altura e a janela tem outra, e a conta sai das duas. Aqui
 * quem sabe a altura do conteúdo é a vista que rola, e há **dois caminhos para lhe
 * perguntar**, porque o `View` esconde os três números (`computeVerticalScrollOffset`
 * e companhia) em métodos protegidos:
 *
 *   - uma `RecyclerView` volta a torná-los públicos, e é por isso que ela entra
 *     como dependência **de compilação apenas**: é a única forma pública de saber
 *     quanto conteúdo há por baixo numa lista que virtualiza;
 *   - um `ScrollView` ou um `NestedScrollView` têm um filho só, e a altura dele é a
 *     do conteúdo. Aí a conta faz-se com `scrollY` e `height`, que são públicos.
 *
 * **A conta final é a mesma da web**, e é isso que faz os dois mapas serem
 * comparáveis. Um ecrã sem nada que role dá 100%: foi visto todo, e não houve nada
 * por baixo para descer, tal como uma página que cabe no visor.
 *
 * E quando nenhum dos dois caminhos sabe responder, **não se inventa**: fica o que
 * se souber com certeza, que é ter chegado ao fim ou não.
 */
internal class Deslocamento(
    private val emitir: (String, String?, Long?, Map<String, Any>?) -> Unit,
    private val agora: () -> Long,
    private val detalhado: () -> Boolean,
    private val individual: () -> Boolean = { false },
) {
    private var maximo = 0
    private var alcancadoEm = 0L
    private var ecraEm = agora()
    private var visorNoMaximo = 0
    private var ecraMedido: String? = null
    /**
     * Nenhum ecrã está aberto antes do primeiro `ecraNovo`. Sem isto, a medição
     * inicial saía carimbada num ecrã que ainda não tinha existido.
     */
    private var aberto = false

    private var raiz: View? = null
    private var ouvinte: ViewTreeObserver.OnScrollChangedListener? = null

    /** A mesma conta da web: o **fundo** do visor sobre o total do conteúdo. */
    fun profundidadeDe(deslocado: Int, visor: Int, conteudo: Int): Int {
        val total = maxOf(1, conteudo)
        val fundo = maxOf(0, deslocado) + maxOf(0, visor)
        return ((fundo.toFloat() / total) * 100).toInt().coerceIn(0, 100)
    }

    private fun medir(v: View?) {
        val alvo = queRola(v) ?: run {
            // Nada rola: o ecrã foi visto todo.
            anotar(100, v?.height ?: 0)
            return
        }
        val medida = medidaDe(alvo)
        if (medida != null) {
            anotar(profundidadeDe(medida.deslocado, medida.visor, medida.conteudo), medida.visor)
            return
        }
        // Não se sabe a altura do conteúdo. O que se sabe com certeza é se já não há
        // nada por baixo, e é só isso que se guarda: uma percentagem inventada aqui
        // seria um mapa de profundidade que ninguém podia contestar.
        if (!alvo.canScrollVertically(1)) anotar(100, alvo.height)
    }

    private fun anotar(p: Int, visor: Int) {
        if (p <= maximo) return
        maximo = p
        alcancadoEm = agora()
        visorNoMaximo = visor
    }

    private class Medida(val deslocado: Int, val visor: Int, val conteudo: Int)

    /**
     * Os três números, pelo caminho que a vista permitir.
     *
     * Devolve `null` quando nenhum deles sabe responder, e quem chama trata disso:
     * **é melhor não desenhar do que desenhar um número inventado**.
     */
    private fun medidaDe(v: View): Medida? {
        // Uma `RecyclerView` torna os três públicos, e é o caso da maioria das
        // listas. A verificação é pelo nome da classe, como a do Compose: sem a
        // dependência presente, o `is` nem compilaria em quem não a tem.
        if (ehRecycler(v)) {
            val off = chamar(v, "computeVerticalScrollOffset")
            val ext = chamar(v, "computeVerticalScrollExtent")
            val ran = chamar(v, "computeVerticalScrollRange")
            if (off != null && ext != null && ran != null && ran > 0) return Medida(off, ext, ran)
        }
        // Um `ScrollView` tem um filho só, e a altura dele é a do conteúdo.
        val grupo = v as? android.view.ViewGroup
        if (grupo != null && grupo.childCount == 1) {
            val conteudo = grupo.getChildAt(0)?.height ?: 0
            if (conteudo > 0) return Medida(v.scrollY, v.height, conteudo)
        }
        return null
    }

    private fun ehRecycler(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        while (c != null) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
        }
        return false
    }

    /** Os métodos existem e são públicos na `RecyclerView`; aqui chamam-se sem a importar. */
    private fun chamar(v: View, nome: String): Int? =
        Seguranca.protegido<Int?>("deslocamento.$nome", null) {
            v.javaClass.getMethod(nome).apply { isAccessible = true }.invoke(v) as? Int
        }

    /**
     * A primeira vista da árvore que sabe rolar.
     *
     * **Procura em profundidade e pára na primeira**, porque é a que ocupa o ecrã.
     * Somar várias dava uma profundidade que não corresponde a nada que alguém
     * tenha visto.
     */
    private fun queRola(v: View?): View? {
        // Um comentário comprido no cartão do inquérito rola, e não é o ecrã.
        if (v == null || v is VistaDoSdk) return null
        if (v.canScrollVertically(1) || v.canScrollVertically(-1)) return v
        val grupo = v as? android.view.ViewGroup ?: return null
        for (i in 0 until grupo.childCount) {
            queRola(grupo.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** Liga-se à árvore do ecrã que acabou de aparecer. */
    fun ligar(raizNova: View?, ecra: String?) {
        desligar()
        raiz = raizNova
        ecraMedido = ecra
        val obs = raizNova?.viewTreeObserver ?: return
        val o = ViewTreeObserver.OnScrollChangedListener {
            Seguranca.executar("deslocamento.scroll") { medir(raiz) }
        }
        ouvinte = o
        Seguranca.executar("deslocamento.ligar") { obs.addOnScrollChangedListener(o) }
        Seguranca.executar("deslocamento.medir") { medir(raizNova) }
    }

    fun desligar() {
        val o = ouvinte ?: return
        Seguranca.executar("deslocamento.desligar") {
            raiz?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(o)
        }
        ouvinte = null
    }

    /** Fecha o ecrã em curso e emite o máximo, se houver um ecrã e um máximo. */
    fun fechar() {
        if (!aberto || maximo <= 0) return
        if (!(detalhado() && individual())) return
        emitir(
            Tipos.DESLOCAMENTO, null, null,
            mapOf(
                "profundidade" to maximo,
                "alcance_ms" to maxOf(0L, (if (alcancadoEm > 0) alcancadoEm else ecraEm) - ecraEm),
                "visor_altura" to visorNoMaximo,
            ),
        )
        aberto = false
    }

    fun ecraNovo(raizNova: View?, ecra: String?) {
        fechar()
        aberto = true
        maximo = 0
        alcancadoEm = 0
        visorNoMaximo = 0
        ecraEm = agora()
        ligar(raizNova, ecra)
    }
}

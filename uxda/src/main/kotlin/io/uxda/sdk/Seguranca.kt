package io.uxda.sdk

/**
 * A barreira de erro do SDK. RNF-SDK-01.
 *
 * É o único requisito do documento escrito com a frase **não tem exceções**:
 * qualquer erro interno é capturado e silenciado. Um SDK que faz a aplicação de
 * outra pessoa rebentar é desinstalado no mesmo dia e nunca mais volta.
 *
 * Em Android isto é mais perigoso do que na web. Um erro dentro de um
 * `ActivityLifecycleCallback` ou de um `Window.Callback` não fica numa consola:
 * mata o processo, e o utilizador vê o diálogo de aplicação parada com o nome da
 * aplicação anfitriã. **Todos** os pontos de entrada passam por aqui, incluindo
 * os ouvintes que o sistema chama.
 */
object Seguranca {

    data class Falha(val quando: Long, val onde: String, val erro: Throwable)

    private const val MAX = 50
    private val registo = ArrayList<Falha>(MAX)

    /** O que aconteceu por dentro, para o diagnóstico. Nunca chega à anfitriã. */
    @JvmStatic
    fun errosInternos(): List<Falha> = synchronized(registo) { registo.toList() }

    @JvmStatic
    fun limpar() = synchronized(registo) { registo.clear() }

    private fun anotar(onde: String, e: Throwable) {
        synchronized(registo) {
            if (registo.size < MAX) registo.add(Falha(System.currentTimeMillis(), onde, e))
        }
    }

    /**
     * Corre e devolve a alternativa se falhar. **Apanha `Throwable`, e não
     * `Exception`**: um `NoSuchMethodError` de uma versão diferente do Compose, ou
     * um `StackOverflowError` a percorrer uma árvore de vistas, matam o processo
     * exatamente da mesma maneira.
     */
    @JvmStatic
    fun <T> protegido(onde: String, alternativa: T, bloco: () -> T): T =
        try {
            bloco()
        } catch (e: Throwable) {
            anotar(onde, e)
            alternativa
        }

    /** Igual, para o que não devolve nada. */
    @JvmStatic
    fun executar(onde: String, bloco: () -> Unit) {
        try {
            bloco()
        } catch (e: Throwable) {
            anotar(onde, e)
        }
    }
}

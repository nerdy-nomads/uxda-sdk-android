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
    fun limpar() {
        synchronized(registo) { registo.clear() }
        synchronized(porReportar) { porReportar.clear() }
    }

    /**
     * Um erro por reportar ao servidor, contado por sítio e por tipo. Cartão 17.3,
     * RF-OPS-10, ADR 0045.
     *
     * **Só o sítio e o nome da classe, e nunca a mensagem**: a mensagem de uma exceção
     * pode trazer o que a pessoa escreveu, e nada que venha de um campo sai do
     * dispositivo (RNF-PRI-01).
     */
    data class ErroAgregado(val onde: String, val tipo: String, val contagem: Int)

    private const val MAX_POR_REPORTAR = 20
    private val porReportar = LinkedHashMap<String, IntArray>()
    private val padraoSitio = Regex("^[A-Za-z0-9_.$:-]{1,80}$")
    private val padraoTipo = Regex("^[A-Za-z0-9_.$:-]{1,60}$")

    private fun contar(onde: String, e: Throwable) {
        val tipo = (e.javaClass.simpleName.takeIf { it.isNotEmpty() } ?: "Throwable").let { if (padraoTipo.matches(it)) it else "Erro" }
        val sitio = if (padraoSitio.matches(onde)) onde else "desconhecido"
        val k = "$sitio|$tipo"
        synchronized(porReportar) {
            val atual = porReportar[k]
            if (atual != null) {
                if (atual[0] < 10000) atual[0]++
            } else if (porReportar.size < MAX_POR_REPORTAR) {
                porReportar[k] = intArrayOf(1)
            }
        }
    }

    /** O que vai no próximo lote. Uma cópia: o que se envia não muda enquanto viaja. */
    @JvmStatic
    fun errosPorReportar(): List<ErroAgregado> = synchronized(porReportar) {
        porReportar.map { (k, n) -> k.split("|", limit = 2).let { ErroAgregado(it[0], it[1], n[0]) } }
    }

    /** Tira o que o servidor já recebeu, e deixa o que entretanto aconteceu. */
    @JvmStatic
    fun confirmarReportados(enviados: List<ErroAgregado>) = synchronized(porReportar) {
        for (e in enviados) {
            val k = "${e.onde}|${e.tipo}"
            val atual = porReportar[k] ?: continue
            atual[0] -= e.contagem
            if (atual[0] <= 0) porReportar.remove(k)
        }
    }

    private fun anotar(onde: String, e: Throwable) {
        synchronized(registo) {
            if (registo.size < MAX) registo.add(Falha(System.currentTimeMillis(), onde, e))
        }
        contar(onde, e)
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

package io.uxda.exemplo

import okhttp3.Interceptor

/**
 * A variante **sem** SDK: a mesma aplicação, o mesmo código de ecrã, o mesmo
 * trabalho de rede, e nenhuma medição. É o termo de comparação do cartão 3.4.
 *
 * **O diagnóstico devolve um texto do mesmo tamanho e da mesma forma**, e isso não
 * é enfeite: é o que torna a comparação honesta. O painel da loja de ensaio escreve
 * este texto numa vista e no registo do sistema de dois em dois segundos, e uma
 * variante que devolvesse `"sem SDK"` estava a desenhar vinte caracteres enquanto a
 * outra desenhava vinte linhas. A primeira medição de bateria foi feita assim, e o
 * que ela mediu foi metade SDK e metade painel: 50% mais CPU, com o painel a fazer
 * boa parte da diferença.
 *
 * Aqui não há SDK nenhum, por isso os valores são os que uma aplicação sem medição
 * tem para dar: nenhuns.
 */
object Ponte {
    // O painel escreve o diagnóstico de dois em dois segundos. Se o texto for
    // sempre igual, o `setText` não obriga a medir nem a desenhar outra vez, e a
    // variante sem SDK poupava um trabalho que a outra fazia. A contagem muda o
    // texto a cada leitura, como mudam os contadores do SDK do outro lado.
    private var leituras = 0

    fun diagnostico(): String = listOf(
        "uxda: sem SDK nesta variante (${++leituras})",
        "  ligado                n/d",
        "  amostrado             n/d",
        "  eventosEmitidos       n/d",
        "  eventosEmFila         n/d",
        "  eventosEntregues      n/d",
        "  eventosPerdidos       n/d",
        "  errosInternos         n/d",
        "  msNoFioPrincipal      n/d",
        "  tempoAtivoMs          n/d",
        "  rede                  n/d",
        "  ecra                  n/d",
        "  sessao                n/d",
    ).joinToString("\n")

    fun identificar(id: String) = Unit
    fun track(nome: String) = Unit
    fun ecra(nome: String) = Unit
    fun intercetor(): Interceptor = Interceptor { cadeia -> cadeia.proceed(cadeia.request()) }
}

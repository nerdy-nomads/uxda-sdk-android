package io.uxda.exemplo

import okhttp3.Interceptor
import okhttp3.Response

/**
 * A variante **sem** SDK: a mesma aplicação, o mesmo código de ecrã, o mesmo
 * trabalho de rede, e nenhuma medição. É o termo de comparação do cartão 3.4.
 */
object Ponte {
    fun diagnostico(): String = "sem SDK nesta variante"
    fun identificar(id: String) = Unit
    fun track(nome: String) = Unit
    fun ecra(nome: String) = Unit
    fun intercetor(): Interceptor = Interceptor { cadeia -> cadeia.proceed(cadeia.request()) }
}

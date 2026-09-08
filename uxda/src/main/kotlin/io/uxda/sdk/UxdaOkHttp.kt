package io.uxda.sdk

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Erros de rede da aplicação anfitriã, para quem usa OkHttp. RF-CAP-04, décimo tipo.
 *
 * ```kotlin
 * OkHttpClient.Builder().addInterceptor(UxdaOkHttp.intercetor()).build()
 * ```
 *
 * Esta classe só é carregada se a aplicação a mencionar, e o OkHttp entra aqui
 * como dependência de compilação apenas: numa aplicação sem OkHttp, nada disto
 * existe em tempo de execução e nada rebenta.
 *
 * **Devolve sempre a resposta original e relança o erro original.** Um SDK que
 * mexa no que o `fetch` da aplicação devolve parte o produto de quem nos instalou.
 */
object UxdaOkHttp {

    fun intercetor(): Interceptor = Interceptor { cadeia ->
        val pedido = cadeia.request()
        try {
            val resposta: Response = cadeia.proceed(pedido)
            // 5xx é falha do servidor; 4xx é a aplicação a dizer que não, e isso é
            // comportamento normal que não se marca como avaria.
            if (resposta.code >= 500) Uxda.erroDeRede(pedido.url.toString(), resposta.code)
            resposta
        } catch (e: Throwable) {
            Uxda.erroDeRede(pedido.url.toString(), 0)
            throw e
        }
    }
}

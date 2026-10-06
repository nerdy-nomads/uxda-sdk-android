package io.uxea.sdk

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Erros de rede da aplicação anfitriã, para quem usa OkHttp. RF-CAP-04, décimo tipo.
 *
 * ```kotlin
 * OkHttpClient.Builder().addInterceptor(UxeaOkHttp.intercetor()).build()
 * ```
 *
 * Esta classe só é carregada se a aplicação a mencionar, e o OkHttp entra aqui
 * como dependência de compilação apenas: numa aplicação sem OkHttp, nada disto
 * existe em tempo de execução e nada rebenta.
 *
 * **Devolve sempre a resposta original e relança o erro original.** Um SDK que
 * mexa no que o `fetch` da aplicação devolve parte o produto de quem nos instalou.
 */
object UxeaOkHttp {

    fun intercetor(): Interceptor = Interceptor { cadeia ->
        val pedido = cadeia.request()
        // Um pedido em voo é a aplicação ocupada: é o que separa um toque que não
        // deu nada de um toque dado **enquanto o sistema estava a trabalhar**
        // (RF-GRA-05), e é o que mede a espera imposta (RF-GRA-21).
        val comecou = System.currentTimeMillis()
        Uxea.pedidoComecou()
        try {
            val resposta: Response = cadeia.proceed(pedido)
            // **Os 4xx contam, e antes não contavam.** A primeira versão dizia que
            // um 4xx é a aplicação a dizer que não, e isso é verdade; só que o
            // RF-MSG-06 pede as respostas de erro do servidor recebidas pelo
            // cliente, e um 422 que ninguém mostra no ecrã é exatamente o abandono
            // que não se explica. O que os separa é a `classe_erro`.
            if (resposta.code >= 400) Uxea.erroDeRede(pedido.url.toString(), resposta.code)
            resposta
        } catch (e: Throwable) {
            // Um pedido que esgotou o tempo é uma coisa diferente de não haver
            // rede: o sistema respondeu tarde, e o utilizador esperou por ele.
            val expirou = e is java.net.SocketTimeoutException || e is java.io.InterruptedIOException
            Uxea.erroDeRede(pedido.url.toString(), 0, expirou)
            throw e
        } finally {
            // Corre sempre, com sucesso ou sem ele: um contador de pedidos em voo
            // que não desce passa a dizer que a aplicação está ocupada para sempre.
            Uxea.pedidoAcabou(System.currentTimeMillis() - comecou)
        }
    }
}

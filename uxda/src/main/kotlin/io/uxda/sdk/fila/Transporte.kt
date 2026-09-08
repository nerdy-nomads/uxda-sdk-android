package io.uxda.sdk.fila

import io.uxda.sdk.Seguranca
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * O envio, com `HttpURLConnection` da plataforma.
 *
 * Sem OkHttp, e não por gosto: o orçamento do RNF-SDK-03 são 300 KB para o SDK
 * inteiro, e o OkHttp sozinho leva mais de 800 KB. Uma aplicação que já o use
 * continua a usá-lo; nós não a obrigamos a levá-lo por nossa causa.
 */
open class Transporte(private val tempoLimiteMs: Int = 15000) {

    data class Resposta(val estado: Int, val corpo: String)

    open fun enviar(url: String, corpo: String, cabecalhos: Map<String, String>, metodo: String = "POST"): Resposta =
        Seguranca.protegido("transporte.enviar", Resposta(0, "")) {
            val ligacao = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = metodo
                connectTimeout = tempoLimiteMs
                readTimeout = tempoLimiteMs
                setRequestProperty("Content-Type", "application/json")
                for ((k, v) in cabecalhos) setRequestProperty(k, v)
                doInput = true
                if (metodo != "GET") {
                    doOutput = true
                    outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
                }
            }
            val estado = ligacao.responseCode
            val fluxo = if (estado in 200..299) ligacao.inputStream else ligacao.errorStream
            val texto = fluxo?.let { BufferedReader(it.reader()).use(BufferedReader::readText) } ?: ""
            ligacao.disconnect()
            Resposta(estado, texto)
        }
}

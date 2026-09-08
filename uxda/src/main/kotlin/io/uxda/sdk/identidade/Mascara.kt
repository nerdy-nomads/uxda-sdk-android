package io.uxda.sdk.identidade

import io.uxda.sdk.Ids
import java.text.Normalizer

/**
 * Mascaramento de valores variáveis dentro de texto visível.
 *
 * É o **mesmo código** do `sdk-js`, regra a regra e pela mesma ordem, e isso não é
 * elegância: o resumo do rótulo entra na identidade do elemento, e a plataforma
 * tem de reconhecer o mesmo botão na web e no telemóvel. Uma regra a mais aqui, ou
 * uma ordem diferente, dá dois elementos onde só há um.
 *
 * Corre no dispositivo, antes de qualquer envio. Nunca se aplica ao que a pessoa
 * escreveu: isso não é capturado de todo (RNF-PRI-01).
 */
object Mascara {

    /** A ordem importa: o mais específico primeiro, senão o número apanha a data. */
    private val REGRAS: List<Pair<Regex, String>> = listOf(
        Regex("""\b\d{4}[-/]\d{1,2}[-/]\d{1,2}\b""") to "{data}",
        Regex("""\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b""") to "{data}",
        Regex("""\b\d{1,2}:\d{2}(:\d{2})?\b""") to "{hora}",
        Regex("""\b[^\s@]+@[^\s@]+\.[^\s@]+\b""") to "{email}",
        Regex("""\b[A-Za-z]{0,3}\d[\dA-Za-z]{7,}\b""") to "{id}",
        Regex("""\b\d+([.,]\d+)?\s*%""") to "{numero}%",
        // Montantes vão para o **mesmo** marcador dos números soltos, de propósito:
        // com marcadores diferentes, "saldo de 12.400 Kz" e "saldo de 300 Kz" davam
        // duas entradas no catálogo, que é a fragmentação que o RF-MSG-03 evita.
        Regex("""\b\d{1,3}([., \s]\d{3})+([.,]\d{1,2})?\b""") to "{numero}",
        Regex("""\b\d+[.,]\d{1,2}\b""") to "{numero}",
        Regex("""\b\d+\b""") to "{numero}",
    )

    fun mascarar(texto: String): String {
        var saida = texto
        for ((re, marcador) in REGRAS) saida = re.replace(saida, marcador)
        return saida
    }

    /**
     * Colapsa espaços, tira diacríticos e baixa a caixa. Sem isto, "Iniciar sessão"
     * e "Iniciar Sessao" seriam elementos diferentes.
     */
    fun normalizarTexto(texto: String): String =
        Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase()

    fun preparar(texto: String): String = mascarar(normalizarTexto(texto))

    /** Normaliza, mascara e resume. É o que entra no sinal do rótulo. */
    fun resumoDe(texto: String): String = Ids.resumo(preparar(texto))
}

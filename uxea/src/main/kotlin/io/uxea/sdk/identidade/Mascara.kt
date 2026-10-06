package io.uxea.sdk.identidade

import io.uxea.sdk.Ids
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

    /**
     * O chão (cartão 18.1): correio e referências longas saem mascarados em qualquer
     * texto, até nos nomes que a aplicação dá a um ecrã, a um passo, a um evento ou a
     * uma mensagem. As mesmas regras do `chao` do SDK web.
     */
    private val CHAO = listOf(
        Regex("""[^\s@/|]+@[^\s@/|]+\.[^\s@/|]+""") to "{email}",
        Regex("""[A-Za-z]{0,3}\d[\dA-Za-z]{7,}""") to "{id}",
        Regex("""\d{6,}""") to "{id}",
    )

    fun chao(texto: String): String {
        var saida = texto
        for ((re, marcador) in CHAO) saida = re.replace(saida, marcador)
        return saida
    }

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

    /* --------------------------------------------------------- mensagens */

    /**
     * O que se tira de uma mensagem além dos números: o que a aplicação lá
     * interpolou a partir do que a pessoa é ou escreveu.
     *
     * O `mascarar` cobre o RF-MSG-04 à letra, que fala de números, montantes, datas
     * e identificadores. Só que uma mensagem real também traz **nomes** ("Olá Ana
     * Maria, o pedido falhou") e **ecos do que foi escrito** ("O valor «abc» não é
     * válido"), e nenhum deles é número nenhum.
     *
     * **A garantia total é a chave, e não isto.** São heurísticas, e a direção do
     * erro é a segura: mascaram a mais, nunca a menos. É a mesma lista do SDK web,
     * pela mesma ordem, porque o catálogo é um só para os dois canais.
     */
    private val REGRAS_MENSAGEM: List<Pair<Regex, String>> = listOf(
        Regex("""[“”«»"]([^“”«»"]{1,120})[“”«»"]""") to "{valor}",
        Regex("""\b(Olá|Ola|Caro|Cara|Exmo\.|Exma\.|Sr\.|Sra\.|Bem-vindo|Bem-vinda)([,]?\s+)\p{Lu}[\p{Ll}\p{M}]+""") to "$1$2{nome}",
        Regex("""\b\p{Lu}[\p{Ll}\p{M}]+(?:\s+(?:d[aeoi]s?|e|von|van|del)\s+\p{Lu}[\p{Ll}\p{M}]+|\s+\p{Lu}[\p{Ll}\p{M}]+)+""") to "{nome}",
    )

    /** Mascara uma mensagem de sistema, no dispositivo e antes de qualquer envio. */
    fun mascararMensagem(texto: String): String {
        var saida = Regex("""\s+""").replace(texto, " ").trim()
        for ((re, marcador) in REGRAS_MENSAGEM) saida = re.replace(saida, marcador)
        return mascarar(saida)
    }

    /**
     * A chave de agrupamento por semelhança (RF-MSG-03). Tira os marcadores e as
     * palavras curtas, e fica com o que a mensagem diz: "O saldo é insuficiente" e
     * "O saldo de {numero} Kz é insuficiente" caem no mesmo grupo.
     */
    fun esqueletoDeMensagem(mascarada: String): String =
        normalizarTexto(mascarada)
            .replace(Regex("""\{[a-z]+\}"""), " ")
            .split(Regex("""[^\p{L}]+"""))
            .filter { it.length >= 4 }
            .take(8)
            .joinToString(" ")
}

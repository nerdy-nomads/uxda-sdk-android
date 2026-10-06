package io.uxea.sdk.inquerito

import io.uxea.sdk.Evento

/**
 * Os critérios dos gatilhos, avaliados **no dispositivo, sobre os eventos que o
 * próprio SDK capturou** (`RF-PER-04`).
 *
 * É o espelho do `condicaoSQL` do motor (`attempt/infra/clickhouse/motor.go`), que é
 * onde as definições de tarefa ganham significado, operador a operador:
 *
 * | Operador | No motor | Aqui |
 * |---|---|---|
 * | `igual`, `diferente` | `col = v`, `col != v` | igualdade exata, com a caixa |
 * | `contem`, `comeca_com` | `position(col, v) > 0`, `startsWith` | `contains`, `startsWith` |
 * | `existe`, `nao_existe` | `col != ''`; `JSONHas` nas propriedades | vazio conta como ausente; a chave presente conta como existente |
 * | `maior`, `menor` | `toFloat64OrNull(col) > v` | um campo que não é número nunca corresponde |
 *
 * **Uma coluna do motor nunca é nula**: o que não veio é a cadeia vazia. Por isso aqui
 * também, e é o que faz um `diferente` sobre um campo ausente corresponder, como lá.
 *
 * # Onde isto diverge do motor, e porquê
 *
 * Nas propriedades com valor numérico. O motor lê-as com `JSONExtractString`, que
 * devolve a cadeia vazia para qualquer valor que não seja texto, e por isso
 * `propriedade:codigo_http maior 499` nunca corresponde lá. Aqui o valor é lido pela
 * forma escrita do número, porque é a única leitura em que `maior` e `menor` sobre as
 * propriedades da captura granular (`hesitacao_ms`, `codigo_http`) querem dizer
 * alguma coisa. A divergência está anotada para o motor, e não copiada para cá.
 */
internal object Condicoes {

    const val IGUAL = "igual"
    const val DIFERENTE = "diferente"
    const val CONTEM = "contem"
    const val COMECA_COM = "comeca_com"
    const val EXISTE = "existe"
    const val NAO_EXISTE = "nao_existe"
    const val MAIOR = "maior"
    const val MENOR = "menor"

    val OPERADORES = setOf(IGUAL, DIFERENTE, CONTEM, COMECA_COM, EXISTE, NAO_EXISTE, MAIOR, MENOR)

    private val CAMPOS = setOf(
        "event_type", "screen_key", "element_key", "message_key", "message_kind", "platform", "app_version",
    )
    private const val PREFIXO = "propriedade:"

    /** A lista fechada do `core/definition`, e `propriedade:` com uma chave a seguir. */
    fun campoValido(campo: String): Boolean =
        campo in CAMPOS || (campo.startsWith(PREFIXO) && campo.length > PREFIXO.length)

    /** Algum dos critérios corresponde. Uma lista vazia não corresponde a nada. */
    fun algum(criterios: List<Criterio>, ev: Evento): Boolean = criterios.any { todas(it, ev) }

    /**
     * Todas as condições do critério correspondem. Um critério sem condições **não
     * corresponde a nada**: o `Validar` do Go recusa-o ao guardar porque
     * corresponderia a tudo, e o dispositivo não o trata de outra maneira.
     */
    fun todas(c: Criterio, ev: Evento): Boolean =
        c.condicoes.isNotEmpty() && c.condicoes.all { corresponde(it, ev) }

    fun corresponde(c: Condicao, ev: Evento): Boolean {
        val propriedade = c.campo.startsWith(PREFIXO)
        val chave = c.campo.removePrefix(PREFIXO)
        val valor = if (propriedade) textoDaPropriedade(ev.properties?.get(chave)) else coluna(c.campo, ev)
        return when (c.operador) {
            IGUAL -> valor == c.valor
            DIFERENTE -> valor != c.valor
            CONTEM -> valor.contains(c.valor)
            COMECA_COM -> valor.startsWith(c.valor)
            EXISTE -> if (propriedade) ev.properties?.containsKey(chave) == true else valor.isNotEmpty()
            NAO_EXISTE -> if (propriedade) ev.properties?.containsKey(chave) != true else valor.isEmpty()
            MAIOR -> valor.toDoubleOrNull()?.let { it > numero(c.valor) } ?: false
            MENOR -> valor.toDoubleOrNull()?.let { it < numero(c.valor) } ?: false
            // Inalcançável: a leitura da configuração recusa operadores desconhecidos.
            else -> false
        }
    }

    /** A coluna do motor, com a cadeia vazia no lugar do que não veio. */
    private fun coluna(campo: String, ev: Evento): String = when (campo) {
        "event_type" -> ev.eventType
        "screen_key" -> ev.screenKey
        "element_key" -> ev.elementKey ?: ""
        "message_key" -> ev.messageKey ?: ""
        "message_kind" -> ev.messageKind ?: ""
        // A plataforma de tudo o que este SDK emite: é a mesma constante do `paraJson`.
        "platform" -> "android"
        "app_version" -> ev.appVersion
        else -> ""
    }

    /** O valor de uma propriedade como texto. Um número inteiro escreve-se sem `.0`. */
    private fun textoDaPropriedade(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> if (v % 1.0 == 0.0 && !v.isInfinite()) v.toLong().toString() else v.toString()
        is Float -> textoDaPropriedade(v.toDouble())
        is Number, is Boolean -> v.toString()
        else -> ""
    }

    /** O `numero` do motor: um valor que não se lê como número compara com zero. */
    private fun numero(v: String): Double = v.trim().toDoubleOrNull()?.takeUnless { it.isNaN() } ?: 0.0
}

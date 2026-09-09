package io.uxda.sdk

import org.json.JSONObject

/**
 * O evento, tal como sai daqui, e as opções de quem integra.
 *
 * O esquema é o canónico do `uxda-core`, e os nomes dos campos são os mesmos do
 * SDK web **letra a letra**: é isso que faz a comparação entre canais da mesma
 * organização (RF-ADM-10) existir. Um `screenKey` em vez de `screen_key` daria
 * dois esquemas para a mesma coisa.
 *
 * O JSON é escrito com o `org.json` da plataforma: vem no `android.jar`, não
 * acrescenta um byte ao pacote de quem nos instala, e o orçamento do RNF-SDK-03
 * não sobrevive a uma biblioteca de serialização.
 */
data class Evento(
    val eventId: String,
    val anonymousId: String,
    val deviceId: String,
    val sessionId: String,
    val eventType: String,
    val screenKey: String,
    val occurredAt: String,
    val appVersion: String,
    val captureLevel: String,
    val userId: String? = null,
    val elementKey: String? = null,
    val durationMs: Long? = null,
    val messageKey: String? = null,
    val messageKind: String? = null,
    /**
     * As propriedades da captura granular (secção 4.16). **Nunca conteúdo de
     * campos**: contagens, tempos e classificações, e a ingestão recusa qualquer
     * chave que não esteja na lista do esquema.
     */
    val properties: Map<String, Any>? = null,
) {
    fun paraJson(): JSONObject = JSONObject().apply {
        put("event_id", eventId)
        put("anonymous_id", anonymousId)
        put("device_id", deviceId)
        put("session_id", sessionId)
        put("event_type", eventType)
        put("screen_key", screenKey)
        put("occurred_at", occurredAt)
        put("app_version", appVersion)
        // A plataforma é `android` e não `web`: é o que separa os dois canais na
        // consulta, e é a única diferença de conteúdo entre os dois SDK.
        put("platform", "android")
        put("identity_scope", "aplicacao")
        put("capture_level", captureLevel)
        userId?.let { put("user_id", it) }
        elementKey?.let { put("element_key", it) }
        durationMs?.let { put("duration_ms", it) }
        messageKey?.let { put("message_key", it) }
        messageKind?.let { put("message_kind", it) }
        properties?.takeIf { it.isNotEmpty() }?.let { put("properties", JSONObject(it)) }
    }

    companion object {
        fun deJson(o: JSONObject): Evento = Evento(
            eventId = o.getString("event_id"),
            anonymousId = o.getString("anonymous_id"),
            deviceId = o.getString("device_id"),
            sessionId = o.getString("session_id"),
            eventType = o.getString("event_type"),
            properties = o.optJSONObject("properties")?.let { p ->
                p.keys().asSequence().associateWith { k -> p.get(k) }
            },
            screenKey = o.getString("screen_key"),
            occurredAt = o.getString("occurred_at"),
            appVersion = o.getString("app_version"),
            captureLevel = o.optString("capture_level", "padrao"),
            userId = o.optString("user_id").ifEmpty { null },
            elementKey = o.optString("element_key").ifEmpty { null },
            durationMs = if (o.has("duration_ms")) o.getLong("duration_ms") else null,
            messageKey = o.optString("message_key").ifEmpty { null },
            messageKind = o.optString("message_kind").ifEmpty { null },
        )
    }
}

/** Os dez tipos do RF-CAP-04, pela ordem do documento. Os mesmos do SDK web. */
object Tipos {
    const val ECRA = "ecra"
    const val TOQUE = "toque"
    const val FOCO = "foco"
    const val TECLA = "tecla"
    const val DESFOCO = "desfoco"
    const val SUBMISSAO = "submissao"
    const val ERRO = "erro"
    const val RECUO = "recuo"
    const val PLANO_FUNDO = "plano_fundo"
    const val ERRO_REDE = "erro_rede"
    const val PERSONALIZADO = "personalizado"

    // Captura granular, secção 4.16 do documento. Tipos próprios e não uma
    // propriedade dentro do `toque`, porque o que se pergunta a estes é "quantos
    // e onde", e o tipo é a única coluna de baixa cardinalidade que responde a
    // isso depressa sobre mil milhões de linhas.
    const val TOQUE_SEM_ALVO = "toque_sem_alvo"
    const val TOQUE_DESATIVADO = "toque_desativado"
    const val TOQUE_REPETIDO = "toque_repetido"
    const val TOQUE_EM_CARREGAMENTO = "toque_em_carregamento"
    const val PRIMEIRA_INTERACAO = "primeira_interacao"
    const val CAMPO = "campo"
    const val PASSO = "passo"
    const val ESPERA = "espera"
    const val TERMINAL = "terminal"
    const val AMBIENTE = "ambiente"
    const val MENSAGEM = "mensagem"

    val TODOS = listOf(ECRA, TOQUE, FOCO, TECLA, DESFOCO, SUBMISSAO, ERRO, RECUO, PLANO_FUNDO, ERRO_REDE)

    /** Os da captura granular, que o cartão 4.5 põe em níveis. */
    val GRANULARES = listOf(
        TOQUE_SEM_ALVO, TOQUE_DESATIVADO, TOQUE_REPETIDO, TOQUE_EM_CARREGAMENTO,
        PRIMEIRA_INTERACAO, CAMPO, PASSO, ESPERA, TERMINAL, AMBIENTE,
    )
}

/** Os quatro estados em que uma tentativa pode acabar (RF-GRA-23). */
object Terminal {
    const val SUCESSO = "sucesso"
    const val ERRO = "erro"
    const val ABANDONADO = "abandonado"
    const val EXPIRADO = "expirado"
}

/**
 * O que o integrador pode dizer. **Só a chave é obrigatória**, e nem essa precisa
 * de código: pode vir do manifesto, e é assim que a integração cabe numa linha.
 */
data class Opcoes(
    val chave: String,
    val servidor: String = "https://ingest.uxda.io",
    val versaoApp: String? = null,
    val automatico: Boolean = true,
)

/** A configuração que muda sem publicar uma versão nova (RF-CAP-09 e RF-CAP-10). */
data class Configuracao(
    val amostragem: Double = 1.0,
    val nivel: String = "padrao",
    val captura: List<String> = emptyList(),
    val versao: Int = 0,
) {
    /**
     * O que cada nível deixa passar. ADR 0010, RF-GRA-26, e a mesma lista do SDK
     * web: uma estimativa de volume calculada sobre listas diferentes estimava
     * outro produto.
     *
     * Repare-se no que o `padrao` tem e no que não tem: tem o **agregado por
     * campo**, e não tem a tecla nem o desfoco. É o RF-GRA-29 inteiro, e é o que
     * decide se o produto é vendável.
     */
    fun capturaTipo(tipo: String): Boolean {
        if (captura.isNotEmpty()) return captura.contains(tipo)
        return when (nivel) {
            "essencial" -> ESSENCIAL.contains(tipo)
            "padrao" -> PADRAO.contains(tipo)
            else -> true
        }
    }

    companion object {
        val ESSENCIAL = setOf(
            Tipos.ECRA, Tipos.TOQUE, Tipos.SUBMISSAO, Tipos.ERRO,
            Tipos.ERRO_REDE, Tipos.MENSAGEM, Tipos.TERMINAL,
        )

        val PADRAO = ESSENCIAL + setOf(
            Tipos.FOCO, Tipos.CAMPO,
            Tipos.TOQUE_SEM_ALVO, Tipos.TOQUE_DESATIVADO, Tipos.TOQUE_REPETIDO,
            Tipos.TOQUE_EM_CARREGAMENTO, Tipos.PRIMEIRA_INTERACAO,
            Tipos.PASSO, Tipos.ESPERA, Tipos.AMBIENTE,
            Tipos.PLANO_FUNDO, Tipos.RECUO, Tipos.PERSONALIZADO,
        )

        /**
         * O valor por omissão **mede tudo**. Uma configuração que não chega não pode
         * deixar o cliente sem dados: a degradação é decisão de quem opera, e nunca
         * um acidente de rede.
         */
        val SEGURA = Configuracao()

        fun deJson(o: JSONObject): Configuracao {
            val amostragem = o.optDouble("amostragem", 1.0).let { if (it in 0.0..1.0) it else 1.0 }
            val nivel = o.optString("nivel", "padrao").let {
                if (it == "essencial" || it == "detalhado") it else "padrao"
            }
            val lista = mutableListOf<String>()
            o.optJSONArray("captura")?.let { a ->
                for (i in 0 until a.length()) {
                    // `opt` e não `optString`: o `optString` converte um número em
                    // texto, e a lista de captura ficava com um "1" a fingir que
                    // era um tipo de evento. O SDK web filtra por tipo, e este tem
                    // de filtrar da mesma maneira, senão as duas configurações
                    // deixam de querer dizer o mesmo.
                    (a.opt(i) as? String)?.takeIf { it.isNotEmpty() }?.let(lista::add)
                }
            }
            return Configuracao(amostragem, nivel, lista, o.optInt("versao", 0))
        }
    }
}

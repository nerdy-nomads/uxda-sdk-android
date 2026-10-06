package io.uxea.sdk.inquerito

import io.uxea.sdk.Contexto
import io.uxea.sdk.Relogio
import io.uxea.sdk.identidade.Mascara
import org.json.JSONArray
import org.json.JSONObject

/** O que a pessoa respondeu, antes de qualquer envio. */
data class Resposta(
    val nota: Int?,
    val escolhas: List<String>,
    val comentario: String,
    val ocorridaEm: Long,
)

/**
 * Um pedido que o servidor autorizou: o inquérito, o gatilho que o trouxe, e o que
 * vai no contexto da resposta.
 */
data class Pedido(
    val inquerito: Inquerito,
    val gatilho: String,
    val tentativaInicio: Long?,
    val pedidoId: String = "",
)

/**
 * Os corpos de `POST /v1/respostas/elegibilidade` e de `POST /v1/respostas`, campo a
 * campo como o contrato os escreve (`docs/contrato-das-respostas.md`).
 *
 * **O corpo da resposta é o mesmo que uma interface própria da instituição
 * enviaria** (`RF-PER-07`), e é por isso que nada aqui é específico do componente
 * além de `origem`: uma resposta dada no cartão e uma dada num ecrã feito em casa
 * entram no mesmo sítio e contam da mesma maneira.
 */
internal object Corpos {

    const val LIMITE_DO_COMENTARIO = 500

    fun elegibilidade(inquerito: String, anonimo: String, utilizador: String?): String =
        JSONObject()
            .put("inquerito", inquerito)
            .put("anonymous_id", anonimo)
            .put("user_id", utilizador ?: "")
            .toString()

    /**
     * O comentário, **mascarado no dispositivo** (`RF-MSG-04`), pela mesma função das
     * mensagens de sistema.
     *
     * E um chão por baixo dela, que só apanha o que o servidor recusaria: um endereço
     * de correio que a expressão do mascaramento deixou passar (`ana@exemplo.` sem
     * nada depois do ponto) e uma corrida de mais de quatro algarismos colada a letras
     * (`ref12345`), onde a fronteira de palavra do `\b` não existe. O servidor recusa
     * em vez de mascarar, e bem: mas uma resposta recusada é uma opinião perdida, e a
     * pessoa não tem como a voltar a dar. O chão é a regra do `TextoComConteudo` do
     * servidor, e não uma regra nova.
     */
    fun mascararComentario(texto: String): String {
        var saida = Mascara.mascararMensagem(texto)
        saida = saida.split(' ').joinToString(" ") { parte ->
            val arroba = parte.indexOf('@')
            if (arroba > 0 && parte.indexOf('.', arroba) > 0) "{email}" else parte
        }
        saida = Regex("""\d{5,}""").replace(saida, "{numero}")
        return saida.take(LIMITE_DO_COMENTARIO)
    }

    /** O que vai no contexto e no dispositivo, e que quem monta o corpo não sabe sozinho. */
    data class Envio(
        val respostaId: String,
        val anonimo: String,
        val utilizador: String?,
        val ecra: String,
        val passo: String,
        val versaoApp: String,
    )

    /**
     * O corpo da resposta, ou `null` quando ela não respeita o formato.
     *
     * A validação repete a do servidor de propósito: uma resposta que ele recusaria não
     * sai daqui, e o erro fica no diagnóstico em vez de num `400` que ninguém lê.
     */
    fun resposta(p: Pedido, r: Resposta, e: Envio, enviadaEm: Long): String? {
        val inq = p.inquerito
        val o = JSONObject()
        o.put("resposta_id", e.respostaId)
        o.put("inquerito", inq.chave)
        o.put("formato", inq.formato)

        val escala = inq.escala
        val escolhas = JSONArray()
        if (escala != null) {
            val nota = r.nota ?: return null
            if (nota !in escala) return null
            o.put("nota", nota)
        } else {
            // A nota é proibida na escolha e no livre, e **a chave fica de fora**, em
            // vez de ir a nulo: um `null` explícito é uma forma de a mandar.
            if (r.nota != null) return null
        }
        if (inq.formato == Formatos.ESCOLHA) {
            val validas = inq.opcoes.map { it.chave }.toSet()
            val unicas = r.escolhas.distinct()
            if (unicas.isEmpty() || unicas.any { it !in validas }) return null
            if (!inq.multipla && unicas.size > 1) return null
            unicas.forEach { escolhas.put(it) }
        } else if (r.escolhas.isNotEmpty()) {
            return null
        }
        o.put("escolhas", escolhas)

        val comentario = if (inq.formato == Formatos.LIVRE || inq.comentario) {
            mascararComentario(r.comentario.trim()).trim()
        } else {
            ""
        }
        if (inq.formato == Formatos.LIVRE && comentario.isEmpty()) return null
        o.put("comentario", comentario)

        o.put("ocorrida_em", Relogio.iso(r.ocorridaEm))
        o.put("enviada_em", Relogio.iso(enviadaEm))
        o.put("pedido_id", p.pedidoId)
        o.put("origem", "componente")

        val contexto = JSONObject()
        contexto.put("tarefa", inq.tarefa)
        // **Só o passo da regra**, que é a chave de um passo da definição da tarefa. O
        // passo em que o dispositivo ia é um nome do SDK (quase sempre o ecrã), e não
        // uma chave da definição: o ensaio no emulador mandou `passo: "/loja"` numa
        // resposta sobre a tarefa inteira, e a análise partia-a num passo que não existe.
        contexto.put("passo", inq.passo)
        contexto.put("funcionalidade", inq.funcionalidade)
        contexto.put("gatilho", p.gatilho)
        contexto.put("ecra", e.ecra.take(256))
        p.tentativaInicio?.let { contexto.put("tentativa_inicio", Relogio.iso(it)) }
        o.put("contexto", contexto)

        // O mesmo contexto dos eventos, com os mesmos quatro sinais e as mesmas
        // ausências: sem modelo, sem versão completa, e o fuso em vez do lugar.
        val dispositivo = JSONObject()
        dispositivo.put("platform", "android")
        dispositivo.put("app_version", e.versaoApp)
        dispositivo.put("os_name", Contexto.osName())
        Contexto.osVersion()?.let { dispositivo.put("os_version", it) }
        dispositivo.put("device_class", Contexto.deviceClass())
        Contexto.timeZone()?.let { dispositivo.put("time_zone", it) }
        o.put("dispositivo", dispositivo)

        o.put("anonymous_id", e.anonimo)
        o.put("user_id", e.utilizador ?: "")
        return o.toString()
    }
}

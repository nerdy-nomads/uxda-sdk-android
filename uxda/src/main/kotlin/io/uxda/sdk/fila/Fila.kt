package io.uxda.sdk.fila

import io.uxda.sdk.Evento
import io.uxda.sdk.Seguranca
import org.json.JSONArray
import org.json.JSONObject

/**
 * Envio em lote, com recuo e com respeito por quem paga o tráfego e a bateria.
 * RF-CAP-07, RNF-SDK-05 e RNF-SDK-07.
 *
 * A regra que manda em tudo o resto: **a aplicação anfitriã não dá por nada**. Se
 * a plataforma estiver em baixo, os eventos ficam em disco e voltam a ser
 * tentados; não há exceção, não há diálogo, não há registo no `Log` de erro.
 */
class Fila(
    private val armazem: Armazem,
    private val transporte: Transporte,
    private val url: String,
    private val cabecalhos: () -> Map<String, String>,
    private val estadoDaRede: () -> EstadoRede,
    private val agora: () -> Long = { System.currentTimeMillis() },
) {

    /** O que o telemóvel diz sobre a rede e sobre a bateria, no momento do envio. */
    data class EstadoRede(val ligado: Boolean, val medida: Boolean, val poupanca: Boolean)

    data class Limites(
        val maxLote: Int = 50,
        val maxBytes: Int = 64 * 1024,
        val intervaloMs: Long = 5_000,
        val recuoMs: Long = 1_000,
        val recuoMaxMs: Long = 5 * 60_000,
        val maxTentativas: Int = 8,
    )

    data class Estado(
        val pendentes: Int, val enviados: Int, val falhas: Int, val perdidos: Int,
        val bytes: Long, val ultimoErro: String, val proximaTentativaEm: Long,
    )

    private var limites = Limites()
    private var tentativas = 0
    private var ultimoEnvio = 0L
    private var proxima = 0L
    private var enviados = 0
    private var falhas = 0
    private var bytes = 0L
    private var ultimoErro = ""

    /**
     * Em rede medida ou com poupança de bateria ligada, o SDK **abranda em vez de
     * desligar**: lotes maiores e menos frequentes gastam menos rádio do que muitos
     * pedidos pequenos, e continuam a não perder nada. Desligar seria mais fácil e
     * daria dados com buracos exatamente nos utilizadores com pior ligação, que são
     * os que mais abandonam.
     */
    private fun limitesAgora(rede: EstadoRede): Limites = when {
        rede.poupanca -> limites.copy(intervaloMs = 120_000, maxLote = 200, maxBytes = 128 * 1024)
        rede.medida -> limites.copy(intervaloMs = 60_000, maxLote = 200, maxBytes = 128 * 1024)
        else -> limites
    }

    fun juntar(ev: Evento) {
        armazem.juntar(ev)
    }

    fun estado(): Estado = Estado(
        pendentes = armazem.quantos(), enviados = enviados, falhas = falhas,
        perdidos = armazem.perdidos(), bytes = bytes, ultimoErro = ultimoErro,
        proximaTentativaEm = proxima,
    )

    /** Está na hora de tentar? Quem decide o quando é o agendador, não a captura. */
    fun podeEnviar(): Boolean {
        val rede = estadoDaRede()
        if (!rede.ligado) return false
        val l = limitesAgora(rede)
        val t = agora()
        return t >= ultimoEnvio + l.intervaloMs && t >= proxima && armazem.quantos() > 0
    }

    /**
     * Envia um lote. **Nunca lança**, e é chamado sempre fora do fio principal.
     * Devolve quantos entraram, para os ensaios poderem afirmar alguma coisa.
     */
    fun descarregar(): Int = Seguranca.protegido("fila.descarregar", 0) {
        val rede = estadoDaRede()
        if (!rede.ligado) return@protegido 0
        val l = limitesAgora(rede)
        val lote = armazem.lote(l.maxLote, l.maxBytes)
        if (lote.isEmpty()) return@protegido 0
        ultimoEnvio = agora()

        val corpo = JSONObject().apply {
            put("versao_protocolo", 1)
            put("sdk", "uxda-sdk-android")
            put("versao_sdk", VERSAO)
            put("enviado_em", io.uxda.sdk.Relogio.iso(agora()))
            put("eventos", JSONArray().also { a -> lote.forEach { a.put(it.paraJson()) } })
        }.toString()

        val r = transporte.enviar(url, corpo, cabecalhos())
        when {
            r.estado in 200..299 -> {
                armazem.confirmar(lote)
                enviados += lote.size
                bytes += corpo.length
                tentativas = 0
                proxima = 0
                ultimoErro = ""
                lote.size
            }
            // Recusa definitiva: repetir não muda nada, e a fila crescia para
            // sempre por causa de uma chave errada de quem integra.
            r.estado in 400..499 && r.estado != 408 && r.estado != 429 -> {
                armazem.confirmar(lote)
                falhas++
                ultimoErro = "recusado ${r.estado}"
                0
            }
            else -> {
                recuar("estado ${r.estado}")
                0
            }
        }
    }

    private fun recuar(motivo: String) {
        falhas++
        ultimoErro = motivo
        tentativas++
        val espera = minOf(limites.recuoMs * (1L shl (tentativas - 1).coerceAtMost(20)), limites.recuoMaxMs)
        // Ao fim das tentativas desiste **desta ronda**, e não da fila: os eventos
        // ficam em disco e saem no próximo arranque. É a diferença entre adiar e
        // perder, e é a única que interessa a quem está a medir abandono.
        proxima = agora() + if (tentativas >= limites.maxTentativas) limites.recuoMaxMs else espera
    }

    companion object {
        const val VERSAO = "0.1.0"
    }
}

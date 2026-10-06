package io.uxea.sdk

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.uxea.sdk.fila.Transporte
import io.uxea.sdk.inquerito.ConfigInqueritos
import io.uxea.sdk.inquerito.Inqueritos
import io.uxea.sdk.inquerito.Pedido
import io.uxea.sdk.inquerito.Resposta
import io.uxea.sdk.inquerito.Tema
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.UUID

/**
 * O que os ensaios do componente de inquérito partilham. Cartões 14.1 e 14.2.
 *
 * **Sem duplos do armazenamento, e com um duplo da rede**: a rede é a fronteira do
 * dispositivo, e é exatamente o que se quer ver (o corpo que sairia, byte a byte). As
 * preferências são as verdadeiras do Robolectric, porque os gatilhos que sobrevivem a
 * um processo abatido só se provam com um disco que guarda.
 */
internal object InqueritoApoio {

    const val ELEGIVEL = """{"sucesso":true,"dados":{"mostrar":true,"motivo":"pode","pedido_id":"p-7f3c"}}"""
    const val ACEITE = """{"sucesso":true,"dados":{"aceite":true,"associada":false}}"""

    /** Guarda cada pedido que sairia do dispositivo, e responde o que o ensaio mandar. */
    class TransporteFalso(
        var elegibilidade: (String) -> Transporte.Resposta = { Transporte.Resposta(200, ELEGIVEL) },
        var respostas: (String) -> Transporte.Resposta = { Transporte.Resposta(202, ACEITE) },
    ) : Transporte() {
        data class Pedido(val url: String, val corpo: String, val cabecalhos: Map<String, String>)

        val pedidos = mutableListOf<Pedido>()

        override fun enviar(url: String, corpo: String, cabecalhos: Map<String, String>, metodo: String): Resposta {
            synchronized(pedidos) { pedidos += Pedido(url, corpo, cabecalhos) }
            return if (url.endsWith("/v1/respostas/elegibilidade")) elegibilidade(corpo) else respostas(corpo)
        }

        fun deElegibilidade() = synchronized(pedidos) { pedidos.filter { it.url.endsWith("/elegibilidade") } }
        fun deRespostas() = synchronized(pedidos) { pedidos.filter { it.url.endsWith("/v1/respostas") } }
    }

    fun prefs(): SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("ensaio-inqueritos-" + UUID.randomUUID(), Context.MODE_PRIVATE)

    fun evento(
        tipo: String = Tipos.ECRA,
        ecra: String = "/loja",
        sessao: String = "s1",
        elemento: String? = null,
        mensagem: String? = null,
        classe: String? = null,
        propriedades: Map<String, Any>? = null,
        versao: String = "1.4.0",
    ) = Evento(
        eventId = UUID.randomUUID().toString(), anonymousId = "a-1", deviceId = "d-1", sessionId = sessao,
        eventType = tipo, screenKey = ecra, occurredAt = "2026-09-14T10:00:00.000Z", appVersion = versao,
        captureLevel = "padrao", elementKey = elemento, messageKey = mensagem, messageKind = classe,
        properties = propriedades,
    )

    /** Um inquérito em JSON, como o `GET /v1/config` o traz. */
    fun inquerito(
        chave: String = "facilidade_do_pagamento",
        formato: String = "esforco",
        gatilho: String = "amostragem",
        criterios: String = "[]",
        inicio: String = "[]",
        extra: String = "",
    ): String = """
        {"chave":"$chave","versao":3,"formato":"$formato",
         "pergunta":{"pt":"Foi fácil pagar a encomenda?","en":"Was it easy to pay for the order?"},
         "opcoes":[],"multipla":false,"comentario":true,"gatilho":"$gatilho",
         "criterios":$criterios,"inicio":$inicio,"atraso_ms":0,
         "contexto":{"tarefa":"pagar_uma_encomenda","passo":"","funcionalidade":""}
         ${if (extra.isNotEmpty()) ",$extra" else ""}}
    """.trimIndent()

    fun config(
        vararg inqueritos: String,
        fadiga: String = """{"max_pedidos":1,"periodo_dias":30,"excluir_respondeu_dias":90}""",
    ): ConfigInqueritos = ConfigInqueritos.deJson(
        JSONObject("""{"tema":{"cor_primaria":"#0b3d2e","cor_fundo":"#ffffff","cor_texto":"#1b1f24","cantos_px":16,"idioma":"pt"},
            "fadiga":$fadiga,"associar_respostas":false,"lista":[${inqueritos.joinToString(",")}]}"""),
    )

    fun componente(
        transporte: Transporte,
        prefs: SharedPreferences = prefs(),
        sorteio: () -> Double = { 0.0 },
        agora: () -> Long = { System.currentTimeMillis() },
        ecra: () -> String = { "/loja" },
        fabricar: ((Activity, Pedido, Tema, (Resposta) -> Unit, () -> Unit) -> View)? = null,
    ): Inqueritos {
        val base = Inqueritos(
            prefs = prefs, transporte = transporte, servidor = "http://10.0.2.2:8710/",
            chaveDoProjeto = "uxea_des_ensaio", versaoApp = { "1.4.0" }, anonimo = { "a-7f3c" },
            utilizador = { null }, ecra = ecra, passo = { "" }, sorteio = sorteio, agora = agora,
            // A rede corre no fio do ensaio: o que interessa é o corpo, e não o fio.
            emRede = { it() },
            esperaAntesDeRepetirMs = 0,
            fabricar = fabricar ?: { a, p, t, r, f -> io.uxea.sdk.inquerito.CartaoDoInquerito(a, p, t, r, f) },
        )
        return base
    }

    /** Deixa correr o fio principal, com o relógio dele a andar. */
    fun andar(ms: Long = 0) {
        val sombra = shadowOf(Looper.getMainLooper())
        if (ms > 0) sombra.idleFor(Duration.ofMillis(ms)) else sombra.idle()
    }

    /** A primeira vista com este texto, ou com esta descrição, debaixo da raiz. */
    fun comTexto(raiz: View, texto: String): View? {
        if (raiz is android.widget.TextView && raiz.text?.toString() == texto) return raiz
        if (raiz is android.view.ViewGroup) {
            for (i in 0 until raiz.childCount) comTexto(raiz.getChildAt(i), texto)?.let { return it }
        }
        return null
    }

    fun centro(v: View): Pair<Float, Float> {
        val pos = IntArray(2)
        v.getLocationOnScreen(pos)
        return (pos[0] + v.width / 2f) to (pos[1] + v.height / 2f)
    }
}

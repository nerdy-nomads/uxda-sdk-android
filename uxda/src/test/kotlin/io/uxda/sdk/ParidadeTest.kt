package io.uxda.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Paridade com o SDK web e com o esquema canónico. Cartão 3.1.
 *
 * O cartão diz porquê, e não é estética: *"se os dois SDK produzirem eventos
 * ligeiramente diferentes, a comparação entre canais da mesma organização
 * (RF-ADM-10) morre antes de nascer"*. Um `screenKey` em vez de `screen_key`
 * bastava.
 *
 * O esquema não está copiado à mão: vem do `uxda-core` pelo `sync-schema.sh`, e o
 * CI falha se as cópias divergirem.
 */
@RunWith(RobolectricTestRunner::class)
class ParidadeTest {

    private fun esquema(): JSONObject {
        val recurso = javaClass.classLoader!!.getResource("schema.json")!!
        return JSONObject(File(recurso.toURI()).readText())
    }

    private fun campos(): Map<String, JSONObject> {
        val out = HashMap<String, JSONObject>()
        val a = esquema().getJSONArray("campos")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            out[c.getString("nome")] = c
        }
        return out
    }

    private val exemplo = Evento(
        eventId = "0f9c2f2e-2f2e-4f2e-8f2e-2f2e2f2e2f2e",
        anonymousId = "a-1", deviceId = "d-1", sessionId = "s-1",
        eventType = Tipos.TOQUE, screenKey = "/loja",
        occurredAt = "2026-09-08T10:00:00.000Z", appVersion = "1.4.0",
        captureLevel = "padrao", userId = "px_abc", elementKey = "v1|f=testid|t=id=pagar",
        durationMs = 1200, messageKey = "validacao_nativa", messageKind = "erro",
    )

    @Test
    fun `os dez tipos sao os do RF-CAP-04, e os mesmos do SDK web`() {
        assertEquals(10, Tipos.TODOS.size)
        // A lista é a do documento, pela ordem do documento, e é a mesma constante
        // que o SDK web exporta em `core/tipos.ts`.
        assertEquals(
            listOf("ecra", "toque", "foco", "tecla", "desfoco", "submissao", "erro", "recuo", "plano_fundo", "erro_rede"),
            Tipos.TODOS,
        )
    }

    @Test
    fun `todo o campo que sai daqui existe no esquema canonico`() {
        val conhecidos = campos().keys
        val json = exemplo.paraJson()
        for (nome in json.keys()) {
            assertTrue("o campo $nome não existe no esquema canónico", conhecidos.contains(nome))
        }
    }

    @Test
    fun `nenhum campo obrigatorio do esquema fica por preencher`() {
        val json = exemplo.paraJson()
        // O projeto e a organização são escritos pela ingestão a partir da chave:
        // o dispositivo não os conhece, e se os conhecesse podia escrever no
        // projeto de outro.
        val escritosPelaIngestao = setOf("project_id", "organization_id", "received_at")
        for ((nome, campo) in campos()) {
            if (!campo.optBoolean("obrigatorio", false)) continue
            if (nome in escritosPelaIngestao) continue
            assertTrue("falta o campo obrigatório $nome", json.has(nome))
        }
    }

    @Test
    fun `a plataforma diz android, e o resto e igual ao web`() {
        val json = exemplo.paraJson()
        assertEquals("android", json.getString("platform"))
        assertEquals("aplicacao", json.getString("identity_scope"))
        assertEquals("/loja", json.getString("screen_key"))
        assertEquals(1200L, json.getLong("duration_ms"))
    }

    @Test
    fun `o evento sobrevive a ida e volta pelo disco`() {
        val volta = Evento.deJson(JSONObject(exemplo.paraJson().toString()))
        assertEquals(exemplo, volta)
    }

    @Test
    fun `o nivel de captura essencial corta o que e frequente e guarda o que dói`() {
        val essencial = Configuracao(nivel = "essencial")
        assertTrue(essencial.capturaTipo(Tipos.ECRA))
        assertTrue(essencial.capturaTipo(Tipos.ERRO))
        assertTrue(essencial.capturaTipo(Tipos.ERRO_REDE))
        assertTrue(essencial.capturaTipo(Tipos.SUBMISSAO))
        assertTrue(!essencial.capturaTipo(Tipos.TOQUE))
        assertTrue(!essencial.capturaTipo(Tipos.FOCO))
        // A lista remota manda sobre o nível, como no SDK web.
        val lista = Configuracao(nivel = "essencial", captura = listOf("toque"))
        assertTrue(lista.capturaTipo(Tipos.TOQUE))
        assertTrue(!lista.capturaTipo(Tipos.ECRA))
    }

    @Test
    fun `configuracao absurda nao passa`() {
        val c = Configuracao.deJson(JSONObject("""{"amostragem": 7, "nivel": "inventado", "captura": [1, "ecra"]}"""))
        assertEquals(1.0, c.amostragem, 0.0)
        assertEquals("padrao", c.nivel)
        assertEquals(listOf("ecra"), c.captura)
    }
}

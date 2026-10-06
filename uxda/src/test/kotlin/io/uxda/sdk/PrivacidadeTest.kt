package io.uxda.sdk

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import io.uxda.sdk.captura.Campos
import org.robolectric.Robolectric

/**
 * Mascaramento por omissão, lista de permissões e consentimento. Cartão 18.1,
 * `RNF-PRI-04`, `RNF-PRI-12`, ADR 0047. O par do `src/privacidade.test.ts` da web.
 */
@RunWith(RobolectricTestRunner::class)
class PrivacidadeTest {

    @Test
    fun `um campo novo, que ninguem declarou, nasce mascarado`() {
        // Acrescentado depois por quem nunca leu a documentação do SDK: sem
        // `contentDescription`, sem etiqueta, sem nada que o declare.
        val eventos = mutableListOf<Evento>()
        val recolher: (String, String?, Long?, Map<String, Any>?) -> Unit = { tipo, el, dur, props ->
            eventos += Evento(eventId = Ids.uuid(), anonymousId = "a1", deviceId = "d1", sessionId = "s1",
                eventType = tipo, screenKey = "/pagamento", occurredAt = "2026-10-06T10:00:00.000Z", appVersion = "1.0.0",
                captureLevel = "padrao", elementKey = el, durationMs = dur, properties = props)
        }
        val atividade = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(atividade)
        atividade.setContentView(raiz)
        var relogio = 1000L
        val campos = Campos(recolher, { relogio += 50; relogio }, { true }, { false })
        val novo = EditText(atividade).also { it.hint = "Número de contribuinte"; raiz.addView(it) }
        campos.entrar(novo)
        novo.setText("500123456")
        campos.sair(novo)

        val bruto = eventos.joinToString("\n") { it.paraJson().toString() }
        assertTrue("o campo novo não foi medido", eventos.any { it.eventType == Tipos.CAMPO && it.properties?.get("caracteres_escritos") == 9 })
        assertFalse("o que se escreveu saiu", bruto.contains("500123456"))
        assertFalse("o rótulo do campo novo saiu em claro", bruto.contains("contribuinte"))
    }

    @Test
    fun `uma propriedade que o esquema nao conhece nao sai, e um texto sai mascarado`() {
        val f = Privacidade.propriedadesDoCliente(
            mapOf("nota_interna" to "José Manuel Ferreira", "segmento" to "Cliente José Manuel Ferreira",
                "valor_monetario" to 12400, "experiencia" to true, "canal" to "conta 500123456"),
            emptyList(),
        )
        assertEquals(listOf("nota_interna"), f.descartadas)
        assertEquals(12400, f.propriedades["valor_monetario"])
        assertEquals(true, f.propriedades["experiencia"])
        assertTrue(f.propriedades["segmento"].toString().contains("{nome}"))
        assertFalse(f.propriedades.toString().contains("José"))
        assertFalse(f.propriedades.toString().contains("500123456"))
    }

    @Test
    fun `so a lista da instituicao levanta a mascara, e nunca o chao`() {
        val f = Privacidade.propriedadesDoCliente(
            mapOf("segmento" to "Empresas Grandes", "canal" to "Balcão Central", "campanha" to "Verão 500123456"),
            listOf("segmento", "campanha"),
        )
        assertEquals("Empresas Grandes", f.propriedades["segmento"])
        assertTrue("uma propriedade não exposta saiu sem máscara", f.propriedades["canal"].toString().contains("{nome}"))
        assertFalse("o chão levantou numa propriedade exposta", f.propriedades["campanha"].toString().contains("500123456"))
    }

    @Test
    fun `o chao vale tambem nos nomes que a aplicacao da`() {
        val m = io.uxda.sdk.identidade.Mascara
        assertEquals("detalhe_{id}", m.chao("detalhe_500123456"))
        // O sublinhado é válido num endereço, e por isso o prefixo vai junto: mascara a mais, que é a direção segura.
        assertEquals("{email}", m.chao("confirmar_jose.ferreira@exemplo.ao"))
        assertEquals("ecra {email} fim", m.chao("ecra ana@exemplo.ao fim"))
        assertEquals("pagou_{id}", m.chao("pagou_AO06000600000100037131174"))
        assertEquals("Pagamento Cartão", m.chao("Pagamento Cartão"))
        assertEquals("passo_2", m.chao("passo_2"))
    }

    @Test
    fun `a configuracao le a lista de permissoes, e a antiga das mensagens soma`() {
        val c = Configuracao.deJson(org.json.JSONObject(
            """{"mensagens_expostas":["a"],"exposicao":{"mensagens":["b"],"propriedades":["segmento"]}}"""))
        assertEquals(listOf("a", "b"), c.mensagensExpostas)
        assertEquals(listOf("segmento"), c.propriedadesExpostas)
        assertEquals(emptyList<String>(), Configuracao.deJson(org.json.JSONObject("{}")).propriedadesExpostas)
    }

    @Test
    fun `com o consentimento exigido e por dar nada corre, e a recusa apaga tudo e fica guardada`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefs = { n: String -> app.getSharedPreferences(n, Context.MODE_PRIVATE) }
        val fila = File(app.filesDir, "uxda")
        try {
            Uxda.iniciar(app, Opcoes(chave = "uxda_des_teste", servidor = "http://127.0.0.1:9", automatico = false, consentimento = "exigido"))
            Uxda.track("antes", mapOf("segmento" to "x"))
            assertEquals("pendente", Uxda.diagnostico()["consentimento"])
            assertEquals(false, Uxda.diagnostico()["ligado"])
            assertTrue("escreveu identificadores sem consentimento", prefs("uxda").all.isEmpty())
            assertFalse("criou a fila sem consentimento", fila.exists())

            // Dado: arranca.
            Uxda.consentimento(true)
            assertEquals("dado", Uxda.diagnostico()["consentimento"])
            assertEquals(true, Uxda.diagnostico()["ligado"])
            assertTrue("arrancou sem identificador", prefs("uxda").all.isNotEmpty())

            // Retirado: para, apaga as preferências e a fila, e guarda só a recusa.
            Uxda.consentimento(false)
            assertEquals("recusado", Uxda.diagnostico()["consentimento"])
            assertEquals(false, Uxda.diagnostico()["ligado"])
            assertTrue("ficaram identificadores depois da recusa", prefs("uxda").all.isEmpty())
            assertFalse("ficou a fila depois da recusa", fila.exists())
            assertEquals("recusado", prefs(Privacidade.PREFS_CONSENTIMENTO).getString("estado", null))

            // E o arranque seguinte, mesmo sem exigir, não mede.
            Uxda.iniciar(app, Opcoes(chave = "uxda_des_teste", servidor = "http://127.0.0.1:9", automatico = false))
            assertEquals(false, Uxda.diagnostico()["ligado"])
            assertEquals("recusado", Uxda.diagnostico()["consentimento"])
        } finally {
            Uxda.parar()
            prefs(Privacidade.PREFS_CONSENTIMENTO).edit().clear().commit()
        }
    }
}

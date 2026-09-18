package io.uxda.sdk

import io.uxda.sdk.InqueritoApoio.config
import io.uxda.sdk.InqueritoApoio.inquerito
import io.uxda.sdk.inquerito.Corpos
import io.uxda.sdk.inquerito.Inquerito
import io.uxda.sdk.inquerito.Pedido
import io.uxda.sdk.inquerito.Resposta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * O corpo de `POST /v1/respostas`, formato a formato. Cartão 14.1, `RF-PER-03` e
 * `RF-PER-07`.
 *
 * **As regras são as do `Validar` do servidor**, repetidas aqui de propósito: uma
 * resposta que ele recusaria não sai do dispositivo, e o defeito vê-se no diagnóstico em
 * vez de num `400` que ninguém lê. Se um dia divergirem, este ensaio é o sítio onde se
 * compara.
 */
@RunWith(RobolectricTestRunner::class)
class InqueritoCorposTest {

    private val envio = Corpos.Envio("4b1d6c1e-5d0f-4a47-9f3b-0a1c5e2d7f10", "a-7f3c", "px_9a", "/pagamento", "dados", "2.4.0")

    private fun inq(formato: String, extra: String = ""): Inquerito =
        config(inquerito(formato = formato, extra = extra)).lista.single()

    private fun corpo(i: Inquerito, nota: Int? = null, escolhas: List<String> = emptyList(), comentario: String = ""): JSONObject? =
        Corpos.resposta(Pedido(i, "amostragem", null, "p-1"), Resposta(nota, escolhas, comentario, 1_789_000_000_000L), envio, 1_789_000_000_118L)
            ?.let { JSONObject(it) }

    @Test
    fun `as tres escalas levam a nota inteira dentro da escala, e escolhas vazias`() {
        for ((formato, escala) in listOf("esforco" to 1..7, "satisfacao" to 1..5, "recomendacao" to 0..10)) {
            val i = inq(formato)
            for (n in escala) {
                val c = corpo(i, nota = n)!!
                assertEquals(formato, c.getString("formato"))
                assertEquals(n, c.getInt("nota"))
                assertEquals(0, c.getJSONArray("escolhas").length())
            }
            assertNull("$formato abaixo da escala", corpo(i, nota = escala.first - 1))
            assertNull("$formato acima da escala", corpo(i, nota = escala.last + 1))
            assertNull("$formato sem nota", corpo(i))
            assertNull("$formato com escolhas", corpo(i, nota = escala.first, escolhas = listOf("x")))
        }
    }

    @Test
    fun `a escolha leva as chaves, e a nota fica de fora do corpo`() {
        val opcoes = """"opcoes":[{"chave":"demorou","pt":"Demorou muito","en":"It took too long"},{"chave":"confuso","pt":"Confuso","en":"Confusing"}]"""
        val unica = inq("escolha", opcoes)
        val c = corpo(unica, escolhas = listOf("confuso"))!!
        assertEquals("confuso", c.getJSONArray("escolhas").getString(0))
        assertFalse("proibida na escolha, e ausente em vez de nula", c.has("nota"))
        assertNull("uma só, sem `multipla`", corpo(unica, escolhas = listOf("demorou", "confuso")))
        assertNull("uma chave que não existe nas opções", corpo(unica, escolhas = listOf("inventada")))
        assertNull("sem nenhuma", corpo(unica))
        assertNull("com nota", corpo(unica, nota = 3, escolhas = listOf("demorou")))

        val m = config(inquerito(formato = "escolha", extra = opcoes).replace("\"multipla\":false", "\"multipla\":true")).lista.single()
        val cm = corpo(m, escolhas = listOf("demorou", "confuso", "demorou"))!!
        assertEquals("sem repetidas", 2, cm.getJSONArray("escolhas").length())
    }

    @Test
    fun `o livre exige o texto, sem nota e sem escolhas`() {
        val i = inq("livre")
        val c = corpo(i, comentario = "  Não encontrei o botão de pagar  ")!!
        assertEquals("Não encontrei o botão de pagar", c.getString("comentario"))
        assertFalse(c.has("nota"))
        assertEquals(0, c.getJSONArray("escolhas").length())
        assertNull("vazio", corpo(i, comentario = "   "))
        assertNull("com nota", corpo(i, nota = 2, comentario = "texto"))
    }

    @Test
    fun `um inquerito sem comentario manda o comentario vazio, mesmo que a pessoa tenha escrito`() {
        // O servidor recusa um comentário num inquérito que não tem campo livre.
        val i = config(inquerito(formato = "satisfacao").replace("\"comentario\":true", "\"comentario\":false")).lista.single()
        assertEquals("", corpo(i, nota = 5, comentario = "isto não devia ir")!!.getString("comentario"))
    }

    @Test
    fun `o corpo leva o contrato inteiro, e o contexto sem conteudo`() {
        val c = Corpos.resposta(
            Pedido(inq("esforco"), "apos_conclusao", 1_789_000_000_000L - 72_392, "b0c1"),
            Resposta(6, emptyList(), "", 1_789_000_000_000L),
            envio, 1_789_000_000_118L,
        )!!.let { JSONObject(it) }
        for (campo in listOf("resposta_id", "inquerito", "formato", "nota", "escolhas", "comentario", "ocorrida_em",
            "enviada_em", "pedido_id", "origem", "contexto", "dispositivo", "anonymous_id", "user_id")) {
            assertTrue("falta $campo", c.has(campo))
        }
        assertEquals("2026-09-10T00:26:40.000Z", c.getString("ocorrida_em"))
        assertEquals("2026-09-10T00:26:40.118Z", c.getString("enviada_em"))
        assertEquals("componente", c.getString("origem"))
        assertEquals("px_9a", c.getString("user_id"))
        val ctx = c.getJSONObject("contexto")
        assertEquals(setOf("tarefa", "passo", "funcionalidade", "gatilho", "ecra", "tentativa_inicio"), ctx.keys().asSequence().toSet())
        assertEquals("sem passo na regra, a resposta não inventa um", "", ctx.getString("passo"))
        assertEquals("2026-09-10T00:25:27.608Z", ctx.getString("tentativa_inicio"))
        val disp = c.getJSONObject("dispositivo")
        assertFalse("nunca o modelo", disp.has("device_model"))
        assertTrue(disp.keys().asSequence().toSet().all { it in setOf("platform", "app_version", "os_name", "os_version", "device_class", "time_zone") })
    }

    @Test
    fun `o comentario sai mascarado, e com o chao que o servidor exige`() {
        val m = Corpos.mascararComentario(
            "O cartão 4111 1111 1111 1111 e o 4111111111111111 falharam, escrevam para ana.silva@exemplo.ao " +
                "ou para ana@exemplo. com a ref12345 da Ana Maria da Silva",
        )
        assertFalse(m, Regex("""\d{5}""").containsMatchIn(m))
        assertFalse(m, m.contains("ana.silva"))
        assertFalse("o `@` com um ponto depois, que o servidor recusa: $m", Regex("""\S@\S*\.""").containsMatchIn(m))
        assertFalse(m, m.contains("Ana Maria"))
        assertTrue(m, m.contains("{email}"))
        // O que não é conteúdo fica como a pessoa o escreveu.
        assertTrue(m, m.startsWith("O cartão"))
        assertTrue(m, m.contains("falharam, escrevam para"))
    }

    @Test
    fun `o comentario fica nos 500 caracteres`() {
        val longo = "palavra ".repeat(200)
        assertEquals(Corpos.LIMITE_DO_COMENTARIO, Corpos.mascararComentario(longo).length)
    }

    @Test
    fun `o pedido de elegibilidade leva so a chave e os identificadores`() {
        val e = JSONObject(Corpos.elegibilidade("facilidade_do_pagamento", "a-7f3c", null))
        assertEquals(setOf("inquerito", "anonymous_id", "user_id"), e.keys().asSequence().toSet())
        assertEquals("", e.getString("user_id"))
    }
}

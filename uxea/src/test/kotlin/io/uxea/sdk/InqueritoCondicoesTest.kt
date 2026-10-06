package io.uxea.sdk

import io.uxea.sdk.InqueritoApoio.evento
import io.uxea.sdk.inquerito.Condicao
import io.uxea.sdk.inquerito.Condicoes
import io.uxea.sdk.inquerito.Criterio
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Os critérios dos gatilhos, operador a operador. Cartão 14.2, `RF-PER-04`.
 *
 * **O que se fixa aqui é o significado do motor**, e não uma leitura nova: uma regra
 * escrita na consola tem de disparar no telemóvel exatamente nos eventos em que a
 * mesma definição corta uma tentativa no servidor. A tabela está no `Condicoes.kt`, ao
 * lado do `condicaoSQL` que ela espelha.
 */
class InqueritoCondicoesTest {

    private fun c(campo: String, operador: String, valor: String = "") = Condicao(campo, operador, valor)

    private val pagar = evento(
        tipo = Tipos.TOQUE, ecra = "/pagamento/confirmacao", elemento = "v1|f=testid|t=id=pagar",
        mensagem = "saldo_insuficiente", classe = "erro",
        propriedades = mapOf("codigo_http" to 503, "estado" to "erro", "hesitacao_ms" to 1250.0, "visivel" to false),
    )

    @Test
    fun `igual e diferente comparam a letra, com a caixa`() {
        assertTrue(Condicoes.corresponde(c("screen_key", "igual", "/pagamento/confirmacao"), pagar))
        assertFalse(Condicoes.corresponde(c("screen_key", "igual", "/Pagamento/Confirmacao"), pagar))
        assertTrue(Condicoes.corresponde(c("event_type", "diferente", "ecra"), pagar))
        assertFalse(Condicoes.corresponde(c("event_type", "diferente", "toque"), pagar))
        assertTrue(Condicoes.corresponde(c("platform", "igual", "android"), pagar))
        assertTrue(Condicoes.corresponde(c("app_version", "igual", "1.4.0"), pagar))
        assertTrue(Condicoes.corresponde(c("message_kind", "igual", "erro"), pagar))
    }

    @Test
    fun `um campo que nao veio e a cadeia vazia, como no motor`() {
        // No motor as colunas nunca são nulas: um `diferente` sobre um campo ausente
        // corresponde, e um `igual` a vazio também.
        val ecra = evento(tipo = Tipos.ECRA)
        assertTrue(Condicoes.corresponde(c("element_key", "diferente", "id=pagar"), ecra))
        assertFalse(Condicoes.corresponde(c("element_key", "igual", "id=pagar"), ecra))
        assertTrue(Condicoes.corresponde(c("propriedade:estado", "diferente", "erro"), ecra))
    }

    @Test
    fun `contem e comeca_com`() {
        assertTrue(Condicoes.corresponde(c("screen_key", "contem", "confirma"), pagar))
        assertFalse(Condicoes.corresponde(c("screen_key", "contem", "carrinho"), pagar))
        assertTrue(Condicoes.corresponde(c("screen_key", "comeca_com", "/pagamento"), pagar))
        assertFalse(Condicoes.corresponde(c("screen_key", "comeca_com", "confirmacao"), pagar))
        assertTrue(Condicoes.corresponde(c("element_key", "contem", "id=pagar"), pagar))
        assertTrue(Condicoes.corresponde(c("propriedade:estado", "comeca_com", "er"), pagar))
    }

    @Test
    fun `existe e nao_existe, nas colunas e nas propriedades`() {
        assertTrue(Condicoes.corresponde(c("message_key", "existe"), pagar))
        assertFalse(Condicoes.corresponde(c("message_key", "nao_existe"), pagar))
        val ecra = evento()
        assertTrue(Condicoes.corresponde(c("message_key", "nao_existe"), ecra))
        assertFalse(Condicoes.corresponde(c("message_key", "existe"), ecra))
        // Nas propriedades é a chave que conta, como o `JSONHas`: um `false` existe.
        assertTrue(Condicoes.corresponde(c("propriedade:visivel", "existe"), pagar))
        assertTrue(Condicoes.corresponde(c("propriedade:campo_abandono", "nao_existe"), pagar))
        assertFalse(Condicoes.corresponde(c("propriedade:visivel", "nao_existe"), pagar))
    }

    @Test
    fun `maior e menor leem numeros, e o que nao e numero nunca corresponde`() {
        assertTrue(Condicoes.corresponde(c("propriedade:codigo_http", "maior", "499"), pagar))
        assertFalse(Condicoes.corresponde(c("propriedade:codigo_http", "menor", "500"), pagar))
        assertTrue(Condicoes.corresponde(c("propriedade:hesitacao_ms", "maior", "1000"), pagar))
        assertTrue(Condicoes.corresponde(c("app_version", "menor", "2"), evento(versao = "1.4")))
        // Um texto não é um número: o `toFloat64OrNull` dá nulo, e nulo não é maior.
        assertFalse(Condicoes.corresponde(c("screen_key", "maior", "0"), pagar))
        assertFalse(Condicoes.corresponde(c("propriedade:inexistente", "menor", "10"), pagar))
        // Um valor de condição que não se lê como número compara com zero, como o
        // `numero()` do motor.
        assertTrue(Condicoes.corresponde(c("propriedade:codigo_http", "maior", "abc"), pagar))
    }

    @Test
    fun `um inteiro guardado como decimal compara como inteiro`() {
        val ev = evento(propriedades = mapOf("codigo_http" to 404.0))
        assertTrue(Condicoes.corresponde(c("propriedade:codigo_http", "igual", "404"), ev))
    }

    @Test
    fun `as condicoes valem em e, e os criterios em ou`() {
        val noEcra = c("screen_key", "igual", "/pagamento/confirmacao")
        val deToque = c("event_type", "igual", "toque")
        val deEcra = c("event_type", "igual", "ecra")
        assertTrue(Condicoes.todas(Criterio(listOf(noEcra, deToque)), pagar))
        assertFalse(Condicoes.todas(Criterio(listOf(noEcra, deEcra)), pagar))
        assertTrue(Condicoes.algum(listOf(Criterio(listOf(noEcra, deEcra)), Criterio(listOf(deToque))), pagar))
        assertFalse(Condicoes.algum(listOf(Criterio(listOf(deEcra))), pagar))
    }

    @Test
    fun `um criterio sem condicoes e uma lista vazia nao correspondem a nada`() {
        // O `Validar` do Go recusa-os porque corresponderiam a tudo. Um inquérito que
        // disparasse em todos os eventos era "perguntar a toda a gente sempre".
        assertFalse(Condicoes.todas(Criterio(emptyList()), pagar))
        assertFalse(Condicoes.algum(emptyList(), pagar))
    }

    @Test
    fun `a lista de campos e fechada`() {
        for (campo in listOf("event_type", "screen_key", "element_key", "message_key", "message_kind", "platform", "app_version", "propriedade:passo")) {
            assertTrue(campo, Condicoes.campoValido(campo))
        }
        for (campo in listOf("anonymous_id", "user_id", "message_text_masked", "propriedade:", "")) {
            assertFalse(campo, Condicoes.campoValido(campo))
        }
        assertTrue(Condicoes.OPERADORES.containsAll(listOf("igual", "diferente", "contem", "comeca_com", "existe", "nao_existe", "maior", "menor")))
        assertTrue(Condicoes.OPERADORES.size == 8)
    }
}

package io.uxda.sdk

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.uxda.sdk.captura.Mensagens
import io.uxda.sdk.identidade.Mascara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Mensagens de sistema em Android. Cartões 5.1, 5.2 e 5.3.
 *
 * A paridade com o SDK web não é intenção: os nomes dos campos, os valores das
 * classificações e as regras de mascaramento são **os mesmos**, e o
 * `ParidadeMensagensTest` compara-os contra o esquema canónico. Sem isso, a mesma
 * mensagem numa aplicação móvel e num sítio da mesma organização dava duas
 * entradas no catálogo, e a comparação entre canais (RF-ADM-10) morria.
 */
@RunWith(RobolectricTestRunner::class)
class MensagensTest {

    private class Saida {
        val eventos = mutableListOf<Triple<String, Map<String, String>, Map<String, Any>?>>()
        val emitir: (String, String?, Map<String, String>, Map<String, Any>?) -> Unit =
            { tipo, _, extras, props -> eventos += Triple(tipo, extras, props) }
        fun mensagens() = eventos.filter { it.first == Tipos.MENSAGEM }
        fun bruto() = eventos.toString()
    }

    private fun atividade(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /**
     * Vistas de mensagem como as bibliotecas reais as escrevem: o `Snackbar` do
     * Material chama-se `SnackbarContentLayout`, e é pelo nome da classe que se
     * reconhece o que ela é. Aqui é a mesma coisa, com nomes portugueses.
     */
    private class VistaDeErro(c: android.content.Context) : TextView(c)
    private class VistaDeAviso(c: android.content.Context) : TextView(c)
    private class VistaDeSucesso(c: android.content.Context) : TextView(c)
    // **Uma vista chamada só "info" não é uma mensagem**, e é de propósito: metade
    // das etiquetas de uma aplicação tem `info` no nome, e contá-las enchia o
    // catálogo de coisas que ninguém mostrou a ninguém. O que a torna mensagem é o
    // continente (`banner`, `notificacao`, `snackbar`), e a `info` é o **tipo**.
    private class BannerInfo(c: android.content.Context) : TextView(c)
    private class NotificacaoInfo(c: android.content.Context) : TextView(c)

    /* --------------------------------------------------------------- 5.1 */

    @Test
    fun `uma vista com nome de erro e uma mensagem, e uma vista qualquer nao e`() {
        val a = atividade()
        val m = Mensagens(Saida().emitir, { 0 })
        val alerta = TextView(a)
        alerta.id = R_ID_ERRO
        assertTrue(m.ehMensagem(alerta))
        assertFalse(m.ehMensagem(TextView(a)))

        val declarada = TextView(a)
        declarada.tag = "uxda:mensagem=saldo_insuficiente"
        assertTrue(m.ehMensagem(declarada))
    }

    @Test
    fun `a chave declarada ganha ao texto, e o texto nem sai`() {
        // RF-MSG-02. Uma aplicação bilingue com captura por texto dá duas entradas
        // no catálogo para o mesmo problema, e a segunda parece metade do tamanho
        // que tem.
        val a = atividade()
        val s = Saida()
        var relogio = 0L
        val m = Mensagens(s.emitir, { relogio })

        val pt = TextView(a).apply { tag = "uxda:mensagem=saldo_insuficiente"; text = "Saldo insuficiente" }
        val raiz = LinearLayout(a).apply { addView(pt) }
        m.varrer(raiz)
        relogio += 2000
        val en = TextView(a).apply { tag = "uxda:mensagem=saldo_insuficiente"; text = "Insufficient balance" }
        raiz.addView(en)
        m.varrer(raiz)

        val evs = s.mensagens()
        assertEquals(2, evs.size)
        assertEquals(setOf("saldo_insuficiente"), evs.map { it.second["message_key"] }.toSet())
        assertNull("com chave, o texto nem sai", evs.first().second["message_text_masked"])
        assertFalse("o texto saiu com a chave presente", s.bruto().contains("Insufficient"))
    }

    @Test
    fun `as quatro classes saem da vista, sem ninguem instrumentar nada`() {
        // É a linha `Pronto quando` do cartão 5.1: uma aplicação que mostra
        // mensagens diferentes produz chaves distintas, classificadas nos quatro
        // tipos, **sem instrumentação**.
        val a = atividade()
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        val raiz = LinearLayout(a).apply {
            addView(VistaDeErro(a).apply { text = "Pagamento recusado pelo banco" })
            addView(VistaDeErro(a).apply { text = "Sessao expirada, entre outra vez" })
            addView(VistaDeAviso(a).apply { text = "A ligacao esta lenta" })
            addView(VistaDeSucesso(a).apply { text = "Transferencia concluida" })
            addView(BannerInfo(a).apply { text = "O comprovativo segue por correio" })
            addView(NotificacaoInfo(a).apply { text = "Guardamos as suas preferencias" })
        }
        m.varrer(raiz)

        val evs = s.mensagens()
        assertEquals(evs.toString(), 6, evs.size)
        assertEquals("duas mensagens diferentes com a mesma chave", 6, evs.map { it.second["message_key"] }.toSet().size)
        assertEquals(
            listOf("aviso", "erro", "erro", "info", "info", "sucesso"),
            evs.mapNotNull { it.second["message_kind"] }.sorted(),
        )
        for (ev in evs) assertEquals(true, ev.third!!["visivel"])
    }

    @Test
    fun `um involucro vazio nao e uma mensagem`() {
        // Uma aplicação tem dezenas de contentores de alerta escondidos à espera de
        // serem preenchidos. Contá-los dava um catálogo com entradas que ninguém viu.
        val a = atividade()
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        val vazio = TextView(a).apply { id = R_ID_ERRO }
        m.varrer(LinearLayout(a).apply { addView(vazio) })
        assertEquals(0, s.mensagens().size)
    }

    @Test
    fun `a mesma mensagem a piscar nao conta vinte vezes`() {
        val a = atividade()
        val s = Saida()
        var relogio = 0L
        val m = Mensagens(s.emitir, { relogio })
        repeat(5) {
            val raiz = LinearLayout(a).apply {
                addView(TextView(a).apply { tag = "uxda:mensagem=rede_lenta"; text = "A ligacao esta lenta" })
            }
            m.varrer(raiz)
            // Passa do intervalo de varredura, mas não da janela de repetição.
            relogio += 300
        }
        assertEquals(1, s.mensagens().size)
    }

    @Test
    fun `o varrimento e travado no tempo`() {
        // Uma aplicação dispõe dezenas de vezes por segundo. Sem o travão, isto
        // sozinho estourava o orçamento do fio principal do RNF-SDK-02.
        val a = atividade()
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        val raiz = LinearLayout(a)
        m.varrer(raiz)
        raiz.addView(TextView(a).apply { tag = "uxda:mensagem=tarde"; text = "Chegou tarde" })
        m.varrer(raiz)
        assertEquals("a segunda varredura no mesmo instante não corre", 0, s.mensagens().size)
    }

    @Test
    fun `o contentor dos avisos nao e uma mensagem, so o que esta la dentro`() {
        // Apanhado no ensaio em telemóvel: o invólucro onde a aplicação empilha os
        // avisos chama-se `avisos`, e o texto dele é a soma dos filhos. Contava
        // duas vezes, uma pelo filho e outra pelo pai, e a segunda com a
        // classificação do invólucro. A mensagem é sempre a mais funda.
        val a = atividade()
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        val avisos = LinearLayout(a).apply {
            id = R_ID_ERRO
            addView(VistaDeErro(a).apply { text = "Pagamento recusado pelo banco" })
        }
        m.varrer(LinearLayout(a).apply { addView(avisos) })

        val evs = s.mensagens()
        assertEquals("o contentor contou também: $evs", 1, evs.size)
        assertEquals("erro", evs.first().second["message_kind"])
    }

    /* --------------------------------------------------------------- 5.2 */

    @Test
    fun `montantes, datas e identificadores saem mascarados do dispositivo`() {
        val a = atividade()
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        val raiz = LinearLayout(a).apply {
            addView(
                TextView(a).apply {
                    id = R_ID_ERRO
                    text = "O saldo de 12.400,50 Kz e insuficiente para o pedido 005123456LA041 de 2027-03-14"
                },
            )
        }
        m.varrer(raiz)

        val ev = s.mensagens().first()
        val texto = ev.second["message_text_masked"]!!
        assertTrue(texto, texto.contains("{numero}"))
        assertTrue(texto, texto.contains("{id}"))
        assertTrue(texto, texto.contains("{data}"))
        for (segredo in listOf("12.400", "12400", "005123456LA041", "2027-03-14")) {
            assertFalse("$segredo atravessou a rede", s.bruto().contains(segredo))
        }
        assertEquals("texto", ev.third!!["origem_mensagem"])
    }

    @Test
    fun `variantes da mesma mensagem caem numa entrada so`() {
        val a = Mascara.mascararMensagem("O saldo de 12.400 Kz é insuficiente")
        val b = Mascara.mascararMensagem("O saldo de 300 Kz é insuficiente")
        assertEquals("duas variantes deram dois textos", a, b)
        val c = Mascara.mascararMensagem("O saldo é insuficiente")
        assertNotEquals(a, c)
        assertEquals(
            "a mesma mensagem reescrita ficou em dois grupos",
            Ids.resumo(Mascara.esqueletoDeMensagem(a)),
            Ids.resumo(Mascara.esqueletoDeMensagem(c)),
        )
        assertNotEquals(
            Ids.resumo(Mascara.esqueletoDeMensagem(a)),
            Ids.resumo(Mascara.esqueletoDeMensagem(Mascara.mascararMensagem("Pagamento recusado pelo banco emissor"))),
        )
    }

    @Test
    fun `nem o nome nem o que a pessoa escreveu saem dentro de uma mensagem`() {
        // É o risco crítico do documento: mensagens de erro com dados pessoais
        // interpolados enviadas sem mascaramento. O `mascarar` do elemento não
        // chegava: um nome não tem um único algarismo.
        val a = atividade()
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        val raiz = LinearLayout(a).apply {
            addView(TextView(a).apply { id = R_ID_ERRO; text = "Ola Ana Maria da Silva, o valor \"conta-secreta\" nao e valido" })
        }
        m.varrer(raiz)
        for (segredo in listOf("Ana Maria", "da Silva", "conta-secreta")) {
            assertFalse("$segredo atravessou a rede", s.bruto().contains(segredo))
        }
        val texto = s.mensagens().first().second["message_text_masked"]!!
        assertTrue(texto, texto.contains("{nome}") && texto.contains("{valor}"))
    }

    @Test
    fun `a lista de permissoes levanta a mascaragem, e o chao fica`() {
        // RNF-PRI-04: mascaramento por omissão, exposição só por lista explícita. E
        // o chão que a lista **não** levanta são os números, os identificadores e o
        // correio electrónico.
        val a = atividade()
        val bruto = "Servico Multicaixa Express indisponivel ate as 18:00 de 2027-03-14"
        val chave = "txt_" + Ids.resumo(Mascara.normalizarTexto(Mascara.mascararMensagem(bruto)))

        val semLista = Saida()
        Mensagens(semLista.emitir, { 0 }).varrer(
            LinearLayout(a).apply { addView(TextView(a).apply { id = R_ID_AVISO; text = bruto }) },
        )
        assertTrue(semLista.mensagens().first().second["message_text_masked"]!!.contains("{hora}"))

        val comLista = Saida()
        Mensagens(comLista.emitir, { 0 }, exposta = { it == chave }).varrer(
            LinearLayout(a).apply { addView(TextView(a).apply { id = R_ID_AVISO; text = bruto }) },
        )
        val exposto = comLista.mensagens().first().second["message_text_masked"]!!
        assertTrue("a lista não expôs nada: $exposto", exposto.contains("Multicaixa Express"))
        assertFalse("o chão foi levantado: $exposto", exposto.contains("18:00"))
        assertFalse("o chão foi levantado: $exposto", exposto.contains("2027-03-14"))
    }

    @Test
    fun `o texto de uma mensagem nunca vem de um campo de escrita`() {
        val a = atividade()
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        val caixa = LinearLayout(a).apply {
            id = R_ID_ERRO
            addView(TextView(a).apply { text = "Confirme o valor" })
            addView(EditText(a).apply { setText("ana.silva@exemplo.ao") })
        }
        m.varrer(LinearLayout(a).apply { addView(caixa) })
        assertFalse("o valor de um campo saiu dentro da mensagem", s.bruto().contains("ana.silva@exemplo.ao"))
        assertTrue(s.mensagens().first().second["message_text_masked"]!!.contains("Confirme o valor"))
    }

    @Test
    fun `o setError de um campo vira mensagem de validacao, mascarada`() {
        // O evento `erro` diz **que campo** falhou e é igual para todos; esta diz
        // **o quê**, e é o que torna a validação contável no catálogo.
        val a = atividade()
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        val campo = EditText(a).apply { hint = "NIF" }
        m.erroDeCampo(campo, "O NIF 005123456LA041 nao existe")

        val ev = s.mensagens().first()
        assertEquals("erro", ev.second["message_kind"])
        assertEquals("validacao", ev.third!!["classe_erro"])
        assertTrue(ev.second["message_text_masked"]!!.contains("{id}"))
        assertFalse(s.bruto().contains("005123456LA041"))
        assertTrue("sem campo associado, sabe-se que falhou e não onde", ev.third!!.containsKey("campo_associado"))
    }

    /* --------------------------------------------------------------- 5.3 */

    @Test
    fun `um erro tecnico sai como invisivel, e e isso que o separa no catalogo`() {
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 })
        m.tecnico("http_503", mapOf("classe_erro" to "sistema", "codigo_http" to 503, "operacao" to "pagamentos"))
        val ev = s.mensagens().first()
        assertEquals(false, ev.third!!["visivel"])
        assertEquals("sistema", ev.third!!["classe_erro"])
        assertEquals(503, ev.third!!["codigo_http"])
        assertEquals("pagamentos", ev.third!!["operacao"])
    }

    @Test
    fun `uma mensagem declarada traz o passo em que a tentativa ia`() {
        val s = Saida()
        val m = Mensagens(s.emitir, { 0 }, passo = { "confirmacao" })
        m.declarar("cartao_recusado", "erro", "pagamento")
        val ev = s.mensagens().first()
        assertEquals("confirmacao", ev.third!!["passo"])
        assertEquals("operacao", ev.third!!["classe_erro"])
        assertEquals("pagamento", ev.third!!["operacao"])
    }

    private companion object {
        /**
         * O que a captura lê é o **nome** do recurso, e não o número: o
         * `android.R.id.message` resolve para `message`, que é o nome que o
         * `AlertDialog` da plataforma dá ao corpo da mensagem.
         */
        val R_ID_ERRO = android.R.id.message
        val R_ID_AVISO = android.R.id.message
    }
}

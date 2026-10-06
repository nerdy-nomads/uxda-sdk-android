package io.uxea.sdk

import io.uxea.sdk.InqueritoApoio.config
import io.uxea.sdk.InqueritoApoio.evento
import io.uxea.sdk.InqueritoApoio.inquerito
import io.uxea.sdk.InqueritoApoio.prefs
import io.uxea.sdk.inquerito.Gatilho
import io.uxea.sdk.inquerito.Gatilhos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Os cinco gatilhos do `RF-PER-04`, um a um. Cartão 14.2.
 *
 * Os dois que atravessam sessões (`apos_abandono` e `primeira_utilizacao`) provam-se
 * **com um `Gatilhos` novo sobre as mesmas preferências**, que é o que acontece quando
 * o sistema abate o processo e a pessoa volta: o estado em memória não chega lá.
 */
@RunWith(RobolectricTestRunner::class)
class InqueritoGatilhosTest {

    private val minuto = 60_000L
    private val hora = 60 * minuto
    private val t0 = 1_789_000_000_000L

    private val noPagamento = """[{"condicoes":[{"campo":"screen_key","operador":"igual","valor":"/pagamento"}]}]"""
    private val naConfirmacao = """[{"condicoes":[{"campo":"screen_key","operador":"igual","valor":"/confirmacao"}]}]"""

    @Test
    fun `apos_conclusao dispara no fim, com o instante do inicio`() {
        val lista = config(inquerito(gatilho = "apos_conclusao", criterios = naConfirmacao, inicio = noPagamento)).lista
        val g = Gatilhos(prefs())
        assertTrue(g.observar(evento(ecra = "/loja"), t0, lista).isEmpty())
        assertTrue(g.observar(evento(ecra = "/pagamento"), t0 + minuto, lista).isEmpty())
        // Voltar ao pagamento não recomeça a tentativa: ela começou antes.
        g.observar(evento(ecra = "/pagamento"), t0 + 2 * minuto, lista)

        val d = g.observar(evento(ecra = "/confirmacao"), t0 + 3 * minuto, lista).single()
        assertEquals(Gatilho.APOS_CONCLUSAO, d.gatilho)
        assertEquals(t0 + minuto, d.tentativaInicio)

        // A mesma conclusão outra vez já não tem tentativa aberta a que se ligar.
        val outra = g.observar(evento(ecra = "/confirmacao"), t0 + 4 * minuto, lista).single()
        assertNull(outra.tentativaInicio)
    }

    @Test
    fun `apos_abandono dispara no arranque da sessao seguinte, e sobrevive ao processo`() {
        val lista = config(inquerito(gatilho = "apos_abandono", criterios = naConfirmacao, inicio = noPagamento)).lista
        val p = prefs()
        val antes = Gatilhos(p)
        antes.observar(evento(ecra = "/loja", sessao = "s1"), t0, lista)
        antes.observar(evento(ecra = "/pagamento", sessao = "s1"), t0 + minuto, lista)
        // O sistema abate o processo aqui: nada de fim, nada de aviso.

        val depois = Gatilhos(p)
        assertTrue("o evento seguinte da mesma sessão não é arranque nenhum",
            depois.observar(evento(ecra = "/loja", sessao = "s1"), t0 + 2 * minuto, lista).isEmpty())
        val d = depois.observar(evento(ecra = "/loja", sessao = "s2"), t0 + 3 * hora, lista).single()
        assertEquals(Gatilho.APOS_ABANDONO, d.gatilho)
        assertEquals(t0 + minuto, d.tentativaInicio)

        assertTrue("o mesmo abandono não se pergunta duas vezes",
            Gatilhos(p).observar(evento(ecra = "/loja", sessao = "s3"), t0 + 4 * hora, lista).isEmpty())
    }

    @Test
    fun `apos_abandono nao dispara se a tarefa acabou, nem passadas vinte e quatro horas`() {
        val lista = config(inquerito(gatilho = "apos_abandono", criterios = naConfirmacao, inicio = noPagamento)).lista

        val acabou = Gatilhos(prefs())
        acabou.observar(evento(ecra = "/pagamento", sessao = "s1"), t0, lista)
        acabou.observar(evento(ecra = "/confirmacao", sessao = "s1"), t0 + minuto, lista)
        assertTrue(acabou.observar(evento(sessao = "s2"), t0 + hora, lista).isEmpty())

        val tarde = Gatilhos(prefs())
        tarde.observar(evento(ecra = "/pagamento", sessao = "s1"), t0, lista)
        assertTrue(tarde.observar(evento(sessao = "s2"), t0 + 25 * hora, lista).isEmpty())
    }

    @Test
    fun `apos_abandono so pergunta pela sessao anterior`() {
        val lista = config(inquerito(gatilho = "apos_abandono", criterios = naConfirmacao, inicio = noPagamento)).lista
        val g = Gatilhos(prefs())
        g.observar(evento(ecra = "/pagamento", sessao = "s1"), t0, lista)
        // A s2 arranca a perguntar pelo abandono da s1, e o evento abre outra tentativa.
        g.observar(evento(ecra = "/pagamento", sessao = "s2"), t0 + hora, lista).let { disparos ->
            assertEquals("a s1 ficou a meio, e a s2 abre-se a perguntar por ela", 1, disparos.size)
        }
        g.observar(evento(ecra = "/pagamento", sessao = "s2"), t0 + hora + minuto, lista)
        val s3 = g.observar(evento(ecra = "/loja", sessao = "s3"), t0 + 2 * hora, lista)
        assertEquals("a s2 também ficou a meio", 1, s3.size)
    }

    @Test
    fun `apos_erro usa o erro por omissao quando a regra nao tem criterios`() {
        val lista = config(inquerito(gatilho = "apos_erro")).lista
        val g = Gatilhos(prefs())
        assertTrue(g.observar(evento(tipo = Tipos.ECRA), t0, lista).isEmpty())
        assertEquals(Gatilho.APOS_ERRO,
            g.observar(evento(tipo = Tipos.ERRO_REDE, mensagem = "http_503", classe = "erro"), t0 + 1, lista).single().gatilho)
        assertEquals(1, g.observar(evento(tipo = Tipos.MENSAGEM, mensagem = "saldo_insuficiente", classe = "erro"), t0 + 2, lista).size)
        assertTrue(g.observar(evento(tipo = Tipos.MENSAGEM, mensagem = "bem_vindo", classe = "info"), t0 + 3, lista).isEmpty())
    }

    @Test
    fun `apos_erro com criterios proprios usa so os dele`() {
        val lista = config(inquerito(gatilho = "apos_erro",
            criterios = """[{"condicoes":[{"campo":"message_key","operador":"igual","valor":"cartao_recusado"}]}]""")).lista
        val g = Gatilhos(prefs())
        assertTrue(g.observar(evento(tipo = Tipos.ERRO_REDE, classe = "erro"), t0, lista).isEmpty())
        assertEquals(1, g.observar(evento(tipo = Tipos.MENSAGEM, mensagem = "cartao_recusado", classe = "erro"), t0 + 1, lista).size)
    }

    @Test
    fun `primeira_utilizacao e a primeira vez neste dispositivo, e nao neste arranque`() {
        val lista = config(inquerito(gatilho = "primeira_utilizacao",
            criterios = """[{"condicoes":[{"campo":"element_key","operador":"contem","valor":"id=exportar"}]}]""")).lista
        val p = prefs()
        val g = Gatilhos(p)
        assertTrue(g.observar(evento(tipo = Tipos.TOQUE, elemento = "v1|f=testid|t=id=pagar"), t0, lista).isEmpty())
        assertEquals(Gatilho.PRIMEIRA_UTILIZACAO,
            g.observar(evento(tipo = Tipos.TOQUE, elemento = "v1|f=testid|t=id=exportar"), t0 + 1, lista).single().gatilho)
        assertTrue(g.observar(evento(tipo = Tipos.TOQUE, elemento = "v1|f=testid|t=id=exportar"), t0 + 2, lista).isEmpty())
        assertTrue("e nem noutro processo, noutra sessão",
            Gatilhos(p).observar(evento(tipo = Tipos.TOQUE, elemento = "v1|f=testid|t=id=exportar", sessao = "s9"), t0 + 3, lista).isEmpty())
    }

    @Test
    fun `amostragem dispara no arranque de cada sessao, e so no arranque`() {
        val lista = config(inquerito(gatilho = "amostragem")).lista
        val g = Gatilhos(prefs())
        assertEquals(Gatilho.AMOSTRAGEM, g.observar(evento(sessao = "s1"), t0, lista).single().gatilho)
        assertTrue(g.observar(evento(tipo = Tipos.TOQUE, sessao = "s1"), t0 + 1, lista).isEmpty())
        assertEquals(1, g.observar(evento(sessao = "s2"), t0 + hora, lista).size)
    }
}

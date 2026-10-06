package io.uxea.sdk

import io.uxea.sdk.InqueritoApoio.inquerito
import io.uxea.sdk.inquerito.CartaoDoInquerito
import io.uxea.sdk.inquerito.ConfigInqueritos
import io.uxea.sdk.inquerito.Fadiga
import io.uxea.sdk.inquerito.Inquerito
import io.uxea.sdk.inquerito.Tema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * O campo `inqueritos` da configuração remota. Cartão 14.1, `RF-PER-01` a `RF-PER-05`.
 *
 * Com Robolectric pela mesma razão do `SegurancaTest`: o `org.json` do `android.jar`
 * dos ensaios é um esboço que devolve nulos, e uma leitura desconfiada testada contra
 * um esboço passava sempre por não ler nada.
 */
@RunWith(RobolectricTestRunner::class)
class InqueritoConfiguracaoTest {

    @Before
    fun limpar() = Seguranca.limpar()

    private fun lista(vararg inqueritos: String) =
        ConfigInqueritos.deJson(JSONObject("""{"lista":[${inqueritos.joinToString(",")}]}""")).lista

    @Test
    fun `sem o campo, ou com a lista vazia, nao se pergunta nada`() {
        assertFalse(Configuracao.deJson(JSONObject("""{"nivel":"padrao"}""")).inqueritos.ativo)
        assertFalse(ConfigInqueritos.deJson(JSONObject("""{"lista":[]}""")).ativo)
        assertFalse(ConfigInqueritos.deJson(null).ativo)
        assertFalse(ConfigInqueritos.deJson("inqueritos").ativo)
    }

    @Test
    fun `as omissoes sao as do contrato`() {
        val semNada = ConfigInqueritos.deJson(JSONObject("""{"lista":[{"chave":"k","formato":"satisfacao","gatilho":"amostragem","pergunta":{"pt":"Gostou?"}}]}"""))
        val inq = semNada.lista.single()
        // **0,1, e nunca "toda a gente sempre".**
        assertEquals(0.1, inq.amostragem, 0.0)
        assertEquals(Inquerito.AMOSTRAGEM_POR_OMISSAO, inq.amostragem, 0.0)
        assertEquals(Fadiga(maxPedidos = 1, periodoDias = 30, excluirRespondeuDias = 90), semNada.fadiga)
        assertEquals(Tema(), semNada.tema)
        assertEquals("pt", semNada.tema.idioma)
        assertEquals(12, semNada.tema.cantosPx)
        assertFalse("a associação só liga com o booleano", semNada.associarRespostas)
        // A pergunta só em português aparece em português nas duas línguas, em vez de
        // aparecer em branco em inglês.
        assertEquals("Gostou?", inq.pergunta(ingles = true))
    }

    @Test
    fun `as cores leem-se como no CSS, com o alfa no fim`() {
        assertEquals(0xFF1F4FD1.toInt(), Tema.cor("#1f4fd1"))
        assertEquals(0xFF1F4FD1.toInt(), Tema.cor("1F4FD1"))
        assertEquals(0xFFAABBCC.toInt(), Tema.cor("#abc"))
        assertEquals("o alfa do CSS vem no fim, e o do Android à frente", 0xCC1F4FD1.toInt(), Tema.cor("#1f4fd1cc"))
        for (mau in listOf("azul", "#12", "#1f4fd1c", "#gggggg", 42, null)) assertNull("$mau", Tema.cor(mau))
    }

    @Test
    fun `o tema recusa o que nao percebe e fica com a omissao`() {
        val t = Tema.deJson(JSONObject("""{"cor_primaria":"vermelho","cor_fundo":"#0b3d2e","cantos_px":400,"idioma":"fr","fonte":42}"""))
        assertEquals(Tema().corPrimaria, t.corPrimaria)
        assertEquals(0xFF0B3D2E.toInt(), t.corFundo)
        assertEquals("acima de 32 é uma cápsula", 32, t.cantosPx)
        assertEquals("pt", t.idioma)
        assertEquals(Tema().fonte, t.fonte)
        assertTrue(Tema.deJson(JSONObject("""{"idioma":"en"}""")).ingles)
    }

    @Test
    fun `a pilha de fontes do CSS da uma familia do sistema`() {
        assertEquals("sans-serif", CartaoDoInquerito.familiaDe("system-ui, sans-serif"))
        assertEquals("serif", CartaoDoInquerito.familiaDe("Georgia, 'Times New Roman', serif"))
        assertEquals("monospace", CartaoDoInquerito.familiaDe("ui-monospace, Menlo, monospace"))
        assertEquals("sans-serif-condensed", CartaoDoInquerito.familiaDe("\"sans-serif-condensed\""))
        assertEquals("sans-serif", CartaoDoInquerito.familiaDe("Fonte Institucional Que Não Existe"))
    }

    @Test
    fun `a fadiga aceita zero dias de descanso, e nao aceita zero pedidos`() {
        val f = Fadiga.deJson(JSONObject("""{"max_pedidos":0,"periodo_dias":-3,"excluir_respondeu_dias":0}"""))
        assertEquals(1, f.maxPedidos)
        assertEquals(30, f.periodoDias)
        assertEquals(0, f.excluirRespondeuDias)
    }

    @Test
    fun `uma condicao ilegivel tira o criterio inteiro, e nao so a condicao`() {
        // Tirar só a condição deixava um critério mais largo do que o escrito, e o
        // inquérito passava a disparar em eventos que ninguém pediu.
        val inq = lista(inquerito(
            gatilho = "apos_erro",
            criterios = """[
              {"condicoes":[{"campo":"screen_key","operador":"igual","valor":"/pagamento"},{"campo":"anonymous_id","operador":"igual","valor":"x"}]},
              {"condicoes":[{"campo":"event_type","operador":"igual","valor":"erro_rede"}]},
              {"condicoes":[{"campo":"screen_key","operador":"parece","valor":"x"}]},
              {"condicoes":[{"campo":"screen_key","operador":"igual"}]},
              {"condicoes":[]}
            ]""",
        )).single()
        assertEquals(1, inq.criterios.size)
        assertEquals("erro_rede", inq.criterios.single().condicoes.single().valor)
    }

    @Test
    fun `um inquerito que nao se consegue responder sai da lista`() {
        val l = lista(
            // Sem pergunta.
            """{"chave":"a","formato":"esforco","gatilho":"amostragem","pergunta":{}}""",
            // Escolha sem opções.
            """{"chave":"b","formato":"escolha","gatilho":"amostragem","pergunta":{"pt":"Porquê?"},"opcoes":[]}""",
            // Formato e gatilho que não existem.
            """{"chave":"c","formato":"estrelas","gatilho":"amostragem","pergunta":{"pt":"?"}}""",
            """{"chave":"d","formato":"esforco","gatilho":"sempre","pergunta":{"pt":"?"}}""",
            // Conclusão sem critérios: dispararia em nada, ou em tudo.
            """{"chave":"e","formato":"esforco","gatilho":"apos_conclusao","pergunta":{"pt":"?"},"criterios":[]}""",
            // Abandono sem início.
            """{"chave":"f","formato":"esforco","gatilho":"apos_abandono","pergunta":{"pt":"?"},"criterios":[{"condicoes":[{"campo":"screen_key","operador":"igual","valor":"/fim"}]}]}""",
            // E um bom, para o ensaio não passar por a lista vir sempre vazia.
            """{"chave":"g","formato":"escolha","gatilho":"amostragem","pergunta":{"pt":"Porquê?"},"multipla":"true","opcoes":[{"chave":"demorou","pt":"Demorou muito","en":"It took too long"},{"chave":"demorou","pt":"repetida"},{"chave":"sem_texto"}]}""",
        )
        assertEquals(listOf("g"), l.map { it.chave })
        val g = l.single()
        assertEquals("só a opção legível e sem repetição", listOf("demorou"), g.opcoes.map { it.chave })
        assertFalse("a cadeia \"true\" não liga a escolha múltipla", g.multipla)
    }

    @Test
    fun `lixo no bloco de inqueritos nao leva a configuracao da captura`() {
        val c = Configuracao.deJson(JSONObject("""{
            "nivel":"essencial","amostragem":0.5,
            "inqueritos":{"tema":[],"fadiga":"x","associar_respostas":"true",
              "lista":[{"chave":{"x":1},"formato":7},"lixo",null,42,
                       {"chave":"k","formato":"esforco","gatilho":"amostragem","pergunta":"não é objeto"}]}
        }"""))
        assertEquals("essencial", c.nivel)
        assertEquals(0.5, c.amostragem, 0.0)
        assertFalse(c.inqueritos.ativo)
        assertFalse(c.inqueritos.associarRespostas)
    }

    @Test
    fun `um bloco que rebenta a ler fica vazio, e o resto da configuracao fica`() {
        // Uma leitura que lança, e não uma que devolve lixo: é a barreira própria do
        // bloco que se prova aqui.
        val traicoeiro = object : JSONObject("""{"nivel":"detalhado","versao":7}""") {
            override fun opt(name: String?): Any? =
                if (name == "inqueritos") throw IllegalStateException("configuração partida") else super.opt(name)
        }
        val c = Configuracao.deJson(traicoeiro)
        assertEquals("detalhado", c.nivel)
        assertEquals(7, c.versao)
        assertFalse(c.inqueritos.ativo)
        assertTrue(Seguranca.errosInternos().any { it.onde == "config.inqueritos" })
    }

    @Test
    fun `a amostragem e o atraso ficam dentro dos limites`() {
        val l = lista(
            inquerito(chave = "a", extra = "\"amostragem\":1.5"),
            inquerito(chave = "b", extra = "\"amostragem\":\"0.9\""),
            inquerito(chave = "c", extra = "\"amostragem\":0"),
        )
        assertEquals(0.1, l[0].amostragem, 0.0)
        assertEquals("um número em texto não é um número", 0.1, l[1].amostragem, 0.0)
        assertEquals("zero é legítimo, e quer dizer ninguém", 0.0, l[2].amostragem, 0.0)
        val atrasado = lista("""{"chave":"x","formato":"livre","gatilho":"amostragem","pergunta":{"pt":"?"},"atraso_ms":999999999,"comentario":true}""").single()
        assertEquals(120_000L, atrasado.atrasoMs)
        assertFalse("no livre o texto é a resposta, e não um extra", atrasado.comentario)
    }
}

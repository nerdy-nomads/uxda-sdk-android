package io.uxda.sdk

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.uxda.sdk.InqueritoApoio.TransporteFalso
import io.uxda.sdk.InqueritoApoio.andar
import io.uxda.sdk.InqueritoApoio.componente
import io.uxda.sdk.InqueritoApoio.config
import io.uxda.sdk.InqueritoApoio.evento
import io.uxda.sdk.InqueritoApoio.inquerito
import io.uxda.sdk.InqueritoApoio.prefs
import io.uxda.sdk.fila.Transporte
import io.uxda.sdk.inquerito.CartaoDoInquerito
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * O componente de inquérito, do gatilho ao corpo que sai. Cartões 14.1 e 14.2,
 * `RF-PER-01` a `RF-PER-05`.
 *
 * O que estes ensaios protegem é a promessa que o `RF-PER-05` faz a quem usa a
 * aplicação: **nunca se pergunta de mais**. Cada ensaio parte uma das condições (o
 * sorteio, a fadiga, o servidor, a sessão) e exige que o cartão não apareça.
 */
@RunWith(RobolectricTestRunner::class)
class InqueritoComponenteTest {

    @Before
    fun limpar() = Seguranca.limpar()

    private lateinit var botaoDaLoja: Button
    private var cliquesNaLoja = 0

    /** A loja, com um botão no topo que tem de continuar a responder com o cartão aberto. */
    private fun loja(): Activity {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        botaoDaLoja = Button(a).apply {
            text = "Pagar 12.400 Kz"
            setOnClickListener { cliquesNaLoja++ }
        }
        raiz.addView(botaoDaLoja, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60))
        a.setContentView(raiz)
        andar()
        return a
    }

    private fun conteudo(a: Activity) = a.findViewById<FrameLayout>(android.R.id.content)

    private fun cartoesEm(a: Activity): List<View> {
        val c = conteudo(a)
        return (0 until c.childCount).map { c.getChildAt(it) }.filterIsInstance<CartaoDoInquerito>()
    }

    /* ---------------------------------------------------- nunca de mais */

    @Test
    fun `sem resposta do servidor nao se mostra`() {
        val a = loja()
        for (resposta in listOf(
            Transporte.Resposta(0, ""),
            Transporte.Resposta(503, """{"sucesso":false}"""),
            Transporte.Resposta(200, "isto não é json"),
            Transporte.Resposta(200, """{"sucesso":true,"dados":{"mostrar":"true","pedido_id":"x"}}"""),
            Transporte.Resposta(200, """{"sucesso":false,"dados":{"mostrar":true}}"""),
        )) {
            val t = TransporteFalso(elegibilidade = { resposta })
            val c = componente(t)
            c.configurar(config(inquerito()))
            c.onActivityResumed(a)
            c.observar(evento(sessao = "s-" + resposta.hashCode()), System.currentTimeMillis())
            andar(5_000)
            assertEquals("perguntou ao servidor", 1, t.deElegibilidade().size)
            assertTrue("mostrou com a resposta $resposta", cartoesEm(a).isEmpty())
            assertEquals("livre", c.faseParaEnsaio())
        }
    }

    @Test
    fun `o servidor diz que nao, e nao se mostra`() {
        val a = loja()
        val t = TransporteFalso(elegibilidade = {
            Transporte.Resposta(200, """{"sucesso":true,"dados":{"mostrar":false,"motivo":"respondeu_recentemente","pedido_id":""}}""")
        })
        val c = componente(t)
        c.configurar(config(inquerito()))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())
        andar(5_000)
        assertTrue(cartoesEm(a).isEmpty())
        assertEquals("respondeu_recentemente", c.resumo()["ultimoMotivo"])
        assertEquals(1, c.resumo()["recusados"])
        assertEquals(0, t.deRespostas().size)
    }

    @Test
    fun `a amostragem por omissao e 0,1, com o sorteio injetado`() {
        val a = loja()
        // O inquérito do apoio não traz `amostragem`: vale a omissão do contrato.
        for ((sorteio, pergunta) in listOf(0.0 to true, 0.0999 to true, 0.1 to false, 0.5 to false, 0.99 to false)) {
            val t = TransporteFalso()
            val c = componente(t, sorteio = { sorteio })
            c.configurar(config(inquerito()))
            c.onActivityResumed(a)
            c.observar(evento(), System.currentTimeMillis())
            assertEquals("sorteio $sorteio", if (pergunta) 1 else 0, t.deElegibilidade().size)
            if (!pergunta) assertEquals("fora_da_amostra", c.resumo()["ultimoMotivo"])
        }
    }

    @Test
    fun `o pedido explicito salta o sorteio, e so o sorteio`() {
        val a = loja()
        val t = TransporteFalso()
        val p = prefs()
        val c = componente(t, prefs = p, sorteio = { 0.999 })
        c.configurar(config(inquerito()))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())
        assertEquals("o gatilho perdeu o sorteio", 0, t.deElegibilidade().size)

        c.pedir("facilidade_do_pagamento")
        andar(100)
        assertEquals(1, t.deElegibilidade().size)
        assertEquals(1, cartoesEm(a).size)

        c.pedir("inquerito_que_nao_existe")
        assertEquals("inexistente", c.resumo()["ultimoMotivo"])
    }

    @Test
    fun `a fadiga do dispositivo poupa o pedido ao servidor`() {
        val a = loja()
        val t = TransporteFalso()
        val p = prefs()
        val c = componente(t, prefs = p)
        c.configurar(config(inquerito()))
        c.onActivityResumed(a)
        c.observar(evento(sessao = "s1"), System.currentTimeMillis())
        andar(100)
        assertEquals(1, t.deElegibilidade().size)
        c.cartaoParaEnsaio()!!.let { (it.parent as ViewGroup).removeView(it) }

        // Sessão nova, no mesmo período: um pedido por trinta dias já foi gasto.
        val outro = componente(t, prefs = p)
        outro.configurar(config(inquerito()))
        outro.onActivityResumed(a)
        outro.observar(evento(sessao = "s2"), System.currentTimeMillis())
        assertEquals("não perguntou ao servidor o que já sabia", 1, t.deElegibilidade().size)
        assertEquals("limite_de_pedidos", outro.resumo()["ultimoMotivo"])

        // Trinta e um dias depois, pode.
        val depois = componente(t, prefs = p, agora = { System.currentTimeMillis() + 31L * 24 * 3600 * 1000 })
        depois.configurar(config(inquerito()))
        depois.observar(evento(sessao = "s3"), System.currentTimeMillis())
        assertEquals(2, t.deElegibilidade().size)
    }

    @Test
    fun `quem respondeu ha pouco nao volta a ser perguntado`() {
        val a = loja()
        val t = TransporteFalso()
        val p = prefs()
        val muitos = """{"max_pedidos":10,"periodo_dias":30,"excluir_respondeu_dias":90}"""
        val c = componente(t, prefs = p)
        c.configurar(config(inquerito(formato = "satisfacao"), fadiga = muitos))
        c.onActivityResumed(a)
        c.observar(evento(sessao = "s1"), System.currentTimeMillis())
        andar(100)
        val cartao = c.cartaoParaEnsaio() as CartaoDoInquerito
        cartao.notaParaEnsaio(4)
        cartao.enviarParaEnsaio()
        assertEquals(1, t.deRespostas().size)

        val seguinte = componente(t, prefs = p)
        seguinte.configurar(config(inquerito(formato = "satisfacao"), fadiga = muitos))
        seguinte.observar(evento(sessao = "s2"), System.currentTimeMillis())
        assertEquals(1, t.deElegibilidade().size)
        assertEquals("respondeu_recentemente", seguinte.resumo()["ultimoMotivo"])
    }

    @Test
    fun `um por sessao, e nunca dois ao mesmo tempo`() {
        val a = loja()
        val t = TransporteFalso()
        val muitos = """{"max_pedidos":10,"periodo_dias":30,"excluir_respondeu_dias":0}"""
        val c = componente(t)
        c.configurar(config(
            inquerito(chave = "a"),
            inquerito(chave = "b", gatilho = "apos_erro"),
            fadiga = muitos,
        ))
        c.onActivityResumed(a)
        c.observar(evento(sessao = "s1"), System.currentTimeMillis())
        andar(100)
        assertEquals(1, cartoesEm(a).size)

        c.observar(evento(tipo = Tipos.ERRO_REDE, classe = "erro", sessao = "s1"), System.currentTimeMillis())
        assertEquals("outro_em_curso", c.resumo()["ultimoMotivo"])
        c.pedir("b")
        assertEquals(1, t.deElegibilidade().size)
        assertEquals(1, cartoesEm(a).size)

        // Fechado o primeiro, a mesma sessão continua sem segunda pergunta.
        InqueritoApoio.comTexto(c.cartaoParaEnsaio()!!, "✕")!!.performClick()
        andar()
        assertTrue(cartoesEm(a).isEmpty())
        c.observar(evento(tipo = Tipos.ERRO_REDE, classe = "erro", sessao = "s1"), System.currentTimeMillis())
        assertEquals("ja_perguntado_na_sessao", c.resumo()["ultimoMotivo"])
        assertEquals(1, t.deElegibilidade().size)
    }

    /* ---------------------------------------------------- o cartão */

    // Um telemóvel do tamanho dos de verdade: no ecrã por omissão do Robolectric (320 por
    // 470) o cartão ocupa quase tudo, e o ensaio não conseguia separar "não bloqueia" de
    // "não havia espaço".
    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `aparece depois do atraso, no fundo, e nao bloqueia o resto do ecra`() {
        val a = loja()
        val t = TransporteFalso()
        val c = componente(t)
        c.configurar(config(inquerito(extra = "\"atraso_ms\":1500").replace("\"atraso_ms\":0,", "")))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())

        andar(1_000)
        assertTrue("apareceu antes do atraso", cartoesEm(a).isEmpty())
        // Uma atividade que retoma a meio do atraso não o encurta.
        c.onActivityResumed(a)
        andar(100)
        assertTrue("a retoma encurtou o atraso", cartoesEm(a).isEmpty())
        andar(600)
        val cartao = cartoesEm(a).single()

        val lp = cartao.layoutParams as FrameLayout.LayoutParams
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, lp.height)
        assertTrue("no fundo do ecrã", lp.gravity and android.view.Gravity.BOTTOM == android.view.Gravity.BOTTOM)
        assertFalse("não roubou o foco", cartao.hasFocus())
        assertEquals("sem véu: o cartão é a única coisa acrescentada", 2, conteudo(a).childCount)

        // O botão da loja continua a responder ao dedo, com o cartão aberto. O ensaio
        // confirma primeiro que o botão não está debaixo do cartão: senão provava outra
        // coisa.
        andar()
        val topoDoCartao = IntArray(2).also { cartao.getLocationOnScreen(it) }[1]
        val fundoDoBotao = IntArray(2).also { botaoDaLoja.getLocationOnScreen(it) }[1] + botaoDaLoja.height
        assertTrue("o botão ficou debaixo do cartão ($fundoDoBotao > $topoDoCartao)", fundoDoBotao < topoDoCartao)
        val (x, y) = InqueritoApoio.centro(botaoDaLoja)
        val agora = SystemClock.uptimeMillis()
        a.window.decorView.dispatchTouchEvent(MotionEvent.obtain(agora, agora, MotionEvent.ACTION_DOWN, x, y, 0))
        a.window.decorView.dispatchTouchEvent(MotionEvent.obtain(agora, agora + 50, MotionEvent.ACTION_UP, x, y, 0))
        andar(100)
        assertEquals("o toque na loja não chegou ao botão", 1, cliquesNaLoja)
        assertNotNull("e o cartão continua lá", c.cartaoParaEnsaio())
    }

    @Test
    fun `o tema da instituicao chega ao cartao`() {
        val a = loja()
        val c = componente(TransporteFalso())
        c.configurar(config(inquerito()))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())
        andar(100)
        val cartao = cartoesEm(a).single()
        val fundo = cartao.background as android.graphics.drawable.GradientDrawable
        assertEquals(0xFFFFFFFF.toInt(), fundo.color!!.defaultColor)
        assertEquals("16 px de cantos em dp", 16f * a.resources.displayMetrics.density, fundo.cornerRadius, 0.01f)
        val enviar = InqueritoApoio.comTexto(cartao, "Enviar")!!
        assertEquals(0xFF0B3D2E.toInt(), (enviar.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertNotNull("em português", InqueritoApoio.comTexto(cartao, "Foi fácil pagar a encomenda?"))
        assertFalse("sem nota, não se envia", enviar.isEnabled)
    }

    @Test
    fun `todas as areas de toque tem pelo menos 48 dp e dizem o que sao`() {
        val a = loja()
        val c = componente(TransporteFalso())
        c.configurar(config(inquerito(formato = "recomendacao")))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())
        andar(100)
        val cartao = cartoesEm(a).single() as ViewGroup
        val minimo = 48 * a.resources.displayMetrics.density - 1
        val notas = ArrayList<android.widget.RadioButton>()
        fun percorrer(v: View) {
            if (v is android.widget.RadioButton) notas += v
            if (v.isClickable && v !is CartaoDoInquerito) {
                assertTrue("${v.javaClass.simpleName} com ${v.height}px de altura", v.height >= minimo)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i))
        }
        percorrer(cartao)
        assertEquals("0 a 10", 11, notas.size)
        assertTrue(notas.all { it.width >= minimo })
        assertEquals("0, numa escala de 0 a 10, nada provável", notas.first().contentDescription)
        assertEquals("10, numa escala de 0 a 10, muito provável", notas.last().contentDescription)
        assertEquals("Fechar o inquérito", InqueritoApoio.comTexto(cartao, "✕")!!.contentDescription)
    }

    @Test
    fun `responder envia o corpo do contrato, e o cartao agradece e sai`() {
        val a = loja()
        val t = TransporteFalso()
        val c = componente(t, ecra = { "/pagamento/confirmacao" })
        c.configurar(config(inquerito(gatilho = "apos_conclusao",
            criterios = """[{"condicoes":[{"campo":"screen_key","operador":"igual","valor":"/pagamento/confirmacao"}]}]""",
            inicio = """[{"condicoes":[{"campo":"screen_key","operador":"igual","valor":"/pagamento"}]}]""")))
        c.onActivityResumed(a)
        val t0 = System.currentTimeMillis()
        c.observar(evento(ecra = "/pagamento"), t0)
        c.observar(evento(ecra = "/pagamento/confirmacao"), t0 + 60_000)
        andar(100)

        val eleg = JSONObject(t.deElegibilidade().single().corpo)
        assertEquals("facilidade_do_pagamento", eleg.getString("inquerito"))
        assertEquals("a-7f3c", eleg.getString("anonymous_id"))
        assertEquals("", eleg.getString("user_id"))
        assertEquals("uxda_des_ensaio", t.deElegibilidade().single().cabecalhos["X-UXDA-Key"])
        assertEquals("http://10.0.2.2:8710/v1/respostas/elegibilidade", t.deElegibilidade().single().url)

        val cartao = cartoesEm(a).single() as CartaoDoInquerito
        cartao.notaParaEnsaio(6)
        cartao.campoParaEnsaio()!!.setText("O código postal 1000-100 foi recusado para ana.silva@exemplo.ao")
        cartao.enviarParaEnsaio()

        val envio = t.deRespostas().single()
        assertEquals("http://10.0.2.2:8710/v1/respostas", envio.url)
        val r = JSONObject(envio.corpo)
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(r.getString("resposta_id")))
        assertEquals("esforco", r.getString("formato"))
        assertEquals(6, r.getInt("nota"))
        assertEquals(0, r.getJSONArray("escolhas").length())
        assertEquals("componente", r.getString("origem"))
        assertEquals("p-7f3c", r.getString("pedido_id"))
        val iso = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""")
        assertTrue(iso.matches(r.getString("ocorrida_em")))
        assertTrue(iso.matches(r.getString("enviada_em")))
        val comentario = r.getString("comentario")
        assertFalse(comentario, comentario.contains("ana.silva"))
        assertFalse(comentario, Regex("""\d{4}""").containsMatchIn(comentario))
        assertTrue(comentario, comentario.contains("{email}"))

        val ctx = r.getJSONObject("contexto")
        assertEquals("pagar_uma_encomenda", ctx.getString("tarefa"))
        assertEquals("apos_conclusao", ctx.getString("gatilho"))
        assertEquals("/pagamento/confirmacao", ctx.getString("ecra"))
        assertEquals(Relogio.iso(t0), ctx.getString("tentativa_inicio"))
        val disp = r.getJSONObject("dispositivo")
        assertEquals("android", disp.getString("platform"))
        assertEquals("1.4.0", disp.getString("app_version"))
        assertEquals("Android", disp.getString("os_name"))
        assertTrue(disp.has("device_class"))
        assertEquals("a-7f3c", r.getString("anonymous_id"))
        assertEquals("", r.getString("user_id"))

        assertNotNull("agradece", InqueritoApoio.comTexto(cartao, "Obrigado pela sua resposta."))
        andar(3_000)
        assertTrue("e sai sozinho", cartoesEm(a).isEmpty())
        assertEquals(1, c.resumo()["enviadas"])
    }

    @Test
    fun `o envio tenta outra vez uma vez, com o mesmo identificador, e uma recusa nao se repete`() {
        val a = loja()
        var chamadas = 0
        val t = TransporteFalso(respostas = {
            chamadas++
            if (chamadas == 1) Transporte.Resposta(503, "") else Transporte.Resposta(202, InqueritoApoio.ACEITE)
        })
        val c = componente(t)
        c.configurar(config(inquerito(formato = "satisfacao")))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())
        andar(100)
        (c.cartaoParaEnsaio() as CartaoDoInquerito).apply { notaParaEnsaio(5); enviarParaEnsaio() }
        val envios = t.deRespostas()
        assertEquals(2, envios.size)
        assertEquals(JSONObject(envios[0].corpo).getString("resposta_id"), JSONObject(envios[1].corpo).getString("resposta_id"))
        assertEquals(1, c.resumo()["enviadas"])

        val a2 = loja()
        val recusa = TransporteFalso(respostas = { Transporte.Resposta(400, """{"sucesso":false}""") })
        val c2 = componente(recusa)
        c2.configurar(config(inquerito(formato = "satisfacao")))
        c2.onActivityResumed(a2)
        c2.observar(evento(), System.currentTimeMillis())
        andar(100)
        (c2.cartaoParaEnsaio() as CartaoDoInquerito).apply { notaParaEnsaio(1); enviarParaEnsaio() }
        assertEquals(1, recusa.deRespostas().size)
        assertEquals("resposta_recusada", c2.resumo()["ultimoMotivo"])

        val a3 = loja()
        val caido = TransporteFalso(respostas = { Transporte.Resposta(0, "") })
        val c3 = componente(caido)
        c3.configurar(config(inquerito(formato = "satisfacao")))
        c3.onActivityResumed(a3)
        c3.observar(evento(), System.currentTimeMillis())
        andar(100)
        (c3.cartaoParaEnsaio() as CartaoDoInquerito).apply { notaParaEnsaio(2); enviarParaEnsaio() }
        assertEquals("uma repetição, e não um ciclo", 2, caido.deRespostas().size)
        assertEquals(1, c3.resumo()["enviosFalhados"])
    }

    @Test
    fun `sem atividade a vista espera pela proxima, e nao para sempre`() {
        val t = TransporteFalso()
        val c = componente(t)
        c.configurar(config(inquerito()))
        c.observar(evento(), System.currentTimeMillis())
        andar(100)
        assertEquals("a_esperar", c.faseParaEnsaio())

        val a = loja()
        c.onActivityResumed(a)
        andar()
        assertEquals(1, cartoesEm(a).size)

        val t2 = TransporteFalso()
        val c2 = componente(t2)
        c2.configurar(config(inquerito()))
        c2.observar(evento(), System.currentTimeMillis())
        andar(61_000)
        assertEquals("livre", c2.faseParaEnsaio())
        assertEquals("sem_atividade", c2.resumo()["ultimoMotivo"])
    }

    @Test
    fun `a rotacao retira o cartao e larga a atividade`() {
        val controlo = Robolectric.buildActivity(Activity::class.java).setup()
        val a = controlo.get()
        val c = componente(TransporteFalso())
        c.configurar(config(inquerito()))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())
        andar(100)
        assertNotNull(c.cartaoParaEnsaio())

        c.onActivityPaused(a)
        controlo.pause().stop().destroy()
        c.onActivityDestroyed(a)
        assertNull("o componente largou o cartão da atividade destruída", c.cartaoParaEnsaio())
        assertEquals("livre", c.faseParaEnsaio())
        assertEquals("atividade_destruida", c.resumo()["ultimoMotivo"])

        // A atividade nova, depois da rotação, não recebe a pergunta outra vez.
        val nova = loja()
        c.onActivityResumed(nova)
        andar(100)
        assertTrue(cartoesEm(nova).isEmpty())
    }

    /* ---------------------------------------------------- a barreira */

    @Test
    fun `um desenho que rebenta nao parte a aplicacao nem prende o componente`() {
        val a = loja()
        val t = TransporteFalso()
        val muitos = """{"max_pedidos":10,"periodo_dias":30,"excluir_respondeu_dias":0}"""
        // Uma vista que rebenta a meio de entrar na árvore: é o pior caso, porque
        // pode ficar lá pendurada.
        val c = componente(t, fabricar = { atividade, _, _, _, _ ->
            object : View(atividade) {
                override fun onAttachedToWindow() {
                    super.onAttachedToWindow()
                    throw NoSuchMethodError("uma versão do sistema sem o método")
                }
            }
        })
        c.configurar(config(inquerito(), fadiga = muitos))
        c.onActivityResumed(a)
        c.observar(evento(sessao = "s1"), System.currentTimeMillis())
        andar(100)

        assertEquals("livre", c.faseParaEnsaio())
        assertEquals("erro_ao_desenhar", c.resumo()["ultimoMotivo"])
        assertEquals("nada ficou pendurado no ecrã", 1, conteudo(a).childCount)
        assertTrue(Seguranca.errosInternos().any { it.onde == "inqueritos.desenhar" })

        // E a aplicação continua de pé: o botão responde.
        botaoDaLoja.performClick()
        assertEquals(1, cliquesNaLoja)
    }

    @Test
    fun `um transporte que lanca nao sobe, e o componente fica livre`() {
        val a = loja()
        val t = object : Transporte() {
            override fun enviar(url: String, corpo: String, cabecalhos: Map<String, String>, metodo: String): Resposta =
                throw OutOfMemoryError("sem memória")
        }
        val c = componente(t)
        c.configurar(config(inquerito()))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())
        andar(100)
        assertEquals("livre", c.faseParaEnsaio())
        assertTrue(cartoesEm(a).isEmpty())
        assertEquals(1, c.resumo()["semResposta"])
    }
}

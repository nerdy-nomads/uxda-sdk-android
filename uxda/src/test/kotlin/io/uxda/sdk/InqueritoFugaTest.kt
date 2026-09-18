package io.uxda.sdk

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import io.uxda.sdk.InqueritoApoio.andar
import io.uxda.sdk.InqueritoApoio.componente
import io.uxda.sdk.InqueritoApoio.config
import io.uxda.sdk.InqueritoApoio.inquerito
import io.uxda.sdk.captura.Captura
import io.uxda.sdk.inquerito.CartaoDoInquerito
import io.uxda.sdk.inquerito.Corpos
import io.uxda.sdk.inquerito.Pedido
import io.uxda.sdk.inquerito.Resposta
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Bateria de fuga do componente de inquérito. Cartão 14.1, `RNF-PRI-01`, `RF-MSG-04`
 * e a regra 3 do contrato das respostas.
 *
 * É a irmã da `FugaTrafegoTest`, com mais uma porta aberta: **o comentário livre é o
 * primeiro sítio do SDK onde uma pessoa escreve para nós**, e não para a aplicação. A
 * bateria corre o percurso inteiro, como no telemóvel: a loja com os campos cheios de
 * segredos, a captura a correr por cima dela, o gatilho a disparar sobre os eventos
 * que a captura produziu, o cartão aberto, um comentário com um cartão bancário e um
 * correio lá dentro, e o envio. Depois lê **todos os bytes que sairiam** (os eventos,
 * o pedido de elegibilidade e a resposta) e procura os segredos.
 */
@RunWith(RobolectricTestRunner::class)
class InqueritoFugaTest {

    /** Os mesmos oito da `FugaTrafegoTest` e do SDK web, escritos na loja. */
    private val daLoja = listOf(
        "005123456LA041", "ana.silva@exemplo.ao", "+244923000111", "4111111111111111",
        "AO06000600000100037131174", "Ana Maria da Silva", "Rua Amilcar Cabral 42", "senha-super-secreta",
    )

    /** O que a pessoa nos escreve a nós, no comentário. */
    private val doComentario =
        "Paguei com o cartão 4111 1111 1111 1111 (ou 5500000000000004) e o recibo não chegou a " +
            "joao.pereira@correio.ao, nem ao Joaquim Manuel dos Santos, referência ref123456789"

    // Nada de pedaços curtos só com algarismos hexadecimais: um "4111" aparece por
    // acaso dentro de um identificador aleatório de um evento, e dava uma fuga que não
    // existe uma vez em cada quarenta corridas.
    private val proibidos = daLoja + listOf(
        "4111 1111", "1111 1111", "5500000000", "joao.pereira", "correio.ao", "Joaquim Manuel", "123456789",
    )

    private fun tudoOQueSairia(eventos: List<Evento>, t: InqueritoApoio.TransporteFalso): String =
        (eventos.map { it.paraJson().toString() } + t.pedidos.map { it.corpo }).joinToString("\n")

    @Test
    fun `nada do que a pessoa escreveu, na loja ou no inquerito, sai do dispositivo`() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val campos = daLoja.mapIndexed { i, _ ->
            EditText(a).apply { hint = "Campo $i" }.also {
                raiz.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40))
            }
        }
        a.setContentView(raiz)

        val t = InqueritoApoio.TransporteFalso()
        val componente = componente(t)
        componente.configurar(config(inquerito(formato = "esforco")))
        componente.onActivityResumed(a)

        // A captura a correr, e cada evento a passar pelo mesmo caminho que o `Uxda`
        // lhe dá: primeiro serializado como a fila o serializa, depois observado
        // pelos gatilhos.
        val eventos = mutableListOf<Evento>()
        val captura = Captura(
            emitir = { tipo, elemento, duracao, extras, props ->
                val ev = Evento(
                    eventId = Ids.uuid(), anonymousId = "a-7f3c", deviceId = "d-1", sessionId = "s1",
                    eventType = tipo, screenKey = "/pagamento", occurredAt = Relogio.iso(System.currentTimeMillis()),
                    appVersion = "1.4.0", captureLevel = "detalhado", elementKey = elemento, durationMs = duracao,
                    messageKey = extras["message_key"], messageKind = extras["message_kind"],
                    messageTextMasked = extras["message_text_masked"], properties = props,
                )
                eventos += ev
                componente.observar(ev, System.currentTimeMillis())
            },
            definirEcra = {},
            nivel = { "detalhado" },
            individual = { true },
        )
        captura.onActivityCreated(a, null)
        captura.onActivityStarted(a)
        captura.onActivityResumed(a)
        andar(200)

        // A loja, preenchida como uma pessoa a preencheria.
        for ((i, c) in campos.withIndex()) {
            c.requestFocus()
            c.setText(daLoja[i])
        }
        // A ação do teclado, que é por onde a captura submete.
        campos.last().let {
            it.requestFocus()
            it.onEditorAction(EditorInfo.IME_ACTION_DONE)
        }

        // O cartão, aberto pelo gatilho sobre os eventos da captura.
        val cartao = componente.cartaoParaEnsaio() as CartaoDoInquerito
        val comentario = cartao.campoParaEnsaio()!!
        comentario.requestFocus()
        comentario.setText(doComentario)
        comentario.onEditorAction(EditorInfo.IME_ACTION_DONE)
        a.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        val nota = todasAsNotas(cartao)[4]
        val (x, y) = InqueritoApoio.centro(nota)
        val agora = SystemClock.uptimeMillis()
        a.window.callback.dispatchTouchEvent(MotionEvent.obtain(agora, agora, MotionEvent.ACTION_UP, x, y, 0))
        cartao.notaParaEnsaio(5)
        cartao.enviarParaEnsaio()
        campos.first().requestFocus()

        // **O que dá valor a tudo o resto**: os caminhos existiram. Sem isto, uma
        // alteração que desligasse a captura ou o envio fazia a bateria passar por não
        // haver nada para encontrar.
        assertEquals("o pedido de elegibilidade", 1, t.deElegibilidade().size)
        assertEquals("a resposta", 1, t.deRespostas().size)
        assertTrue("sem agregados de campo da loja", eventos.any { it.eventType == Tipos.CAMPO && (it.properties?.get("caracteres_escritos") as? Int ?: 0) > 0 })
        assertTrue("sem submissão da loja", eventos.any { it.eventType == Tipos.SUBMISSAO })
        val enviado = JSONObject(t.deRespostas().single().corpo).getString("comentario")
        assertTrue("o comentário chegou vazio, e a bateria não provava nada: $enviado", enviado.length > 40)
        assertTrue(enviado, enviado.contains("{email}") && enviado.contains("{numero}"))

        val bruto = tudoOQueSairia(eventos, t)
        for (segredo in proibidos) {
            assertFalse("$segredo saiu do dispositivo", bruto.contains(segredo))
        }
        // E o que o servidor recusaria, que é a contraprova do dispositivo.
        assertFalse("uma corrida de mais de quatro algarismos: $enviado", Regex("""\d{5}""").containsMatchIn(enviado))
        assertFalse("um correio por mascarar: $enviado", enviado.contains("@"))
        println("  14.1 fuga Android: ${eventos.size} eventos, ${t.pedidos.size} pedidos, comentário enviado: $enviado")
    }

    @Test
    fun `a bateria apanha uma fuga introduzida de proposito`() {
        // O mesmo corpo, montado por alguém que se esqueceu de mascarar: tem de ser
        // apanhado, senão a bateria de cima não serve para nada.
        val inq = config(inquerito(formato = "esforco")).lista.single()
        val corpo = JSONObject(
            Corpos.resposta(
                Pedido(inq, "amostragem", null, "p-1"),
                Resposta(nota = 5, escolhas = emptyList(), comentario = "sem nada", ocorridaEm = 0),
                Corpos.Envio("00000000-0000-4000-8000-000000000001", "a", null, "/x", "", "1"),
                0,
            )!!,
        ).put("comentario", doComentario).toString()
        val t = InqueritoApoio.TransporteFalso()
        t.enviar("http://x/v1/respostas", corpo, emptyMap())
        val bruto = tudoOQueSairia(emptyList(), t)
        assertTrue("a bateria não apanharia uma fuga", proibidos.any { bruto.contains(it) })
    }

    private fun todasAsNotas(raiz: View): List<RadioButton> {
        val saida = ArrayList<RadioButton>()
        fun percorrer(v: View) {
            if (v is RadioButton) saida += v
            if (v is ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i))
        }
        percorrer(raiz)
        return saida
    }
}

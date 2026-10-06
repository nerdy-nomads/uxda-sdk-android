package io.uxea.sdk

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import io.uxea.sdk.InqueritoApoio.andar
import io.uxea.sdk.InqueritoApoio.componente
import io.uxea.sdk.InqueritoApoio.config
import io.uxea.sdk.InqueritoApoio.evento
import io.uxea.sdk.InqueritoApoio.inquerito
import io.uxea.sdk.captura.Captura
import io.uxea.sdk.captura.VistaDoSdk
import io.uxea.sdk.identidade.Elemento
import io.uxea.sdk.inquerito.CartaoDoInquerito
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * **O cartão do inquérito não aparece nas métricas da aplicação que o mostra.**
 * Cartão 14.1.
 *
 * É a primeira interface que o SDK desenha dentro de uma aplicação alheia, e a captura
 * percorre a árvore inteira: sem a marca `VistaDoSdk`, o toque numa nota saía como um
 * `toque` da loja, o comentário como um `campo` com contagens de caracteres, e a
 * primeira interação do ecrã passava a ser a nossa pergunta. Cada ensaio aqui faz uma
 * dessas coisas **no cartão e na loja**, e exige que só a da loja saia: sem a metade
 * da loja, um ensaio que passasse podia estar só a provar que a captura não corria.
 */
@RunWith(RobolectricTestRunner::class)
class InqueritoCapturaTest {

    private data class Emitido(val tipo: String, val elemento: String?, val extras: Map<String, String>, val props: Map<String, Any>?)

    private val emitidos = mutableListOf<Emitido>()
    private lateinit var a: Activity
    private lateinit var captura: Captura
    private lateinit var botao: Button
    private lateinit var campo: EditText
    private lateinit var cartao: CartaoDoInquerito

    private fun montar() {
        a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        botao = Button(a).apply { text = "Pagar"; id = View.generateViewId() }
        campo = EditText(a).apply { hint = "Nome no cartão" }
        raiz.addView(botao, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60))
        raiz.addView(campo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60))
        a.setContentView(raiz)

        captura = Captura(
            emitir = { tipo, elemento, _, extras, props -> emitidos += Emitido(tipo, elemento, extras, props) },
            definirEcra = {},
        )
        captura.onActivityCreated(a, null)
        captura.onActivityStarted(a)
        captura.onActivityResumed(a)

        val c = componente(InqueritoApoio.TransporteFalso())
        c.configurar(config(inquerito(formato = "satisfacao")))
        c.onActivityResumed(a)
        c.observar(evento(), System.currentTimeMillis())
        andar(200)
        cartao = c.cartaoParaEnsaio() as CartaoDoInquerito
        emitidos.clear()
    }

    private fun tocar(v: View) {
        val (x, y) = InqueritoApoio.centro(v)
        val t = SystemClock.uptimeMillis()
        a.window.callback.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun todas(raiz: View, tipo: Class<*>): List<View> {
        val saida = ArrayList<View>()
        fun percorrer(v: View) {
            if (tipo.isInstance(v)) saida += v
            if (v is ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i))
        }
        percorrer(raiz)
        return saida
    }

    @Test
    fun `um toque no cartao nao e um toque da aplicacao`() {
        montar()
        assertTrue(cartao is VistaDoSdk)
        val nota = todas(cartao, RadioButton::class.java).first()
        tocar(nota)
        tocar(InqueritoApoio.comTexto(cartao, "Enviar")!!)
        // A margem do cartão, onde não há nada acionável: fora do cartão seria um
        // `toque_sem_alvo`.
        val (x, _) = InqueritoApoio.centro(cartao)
        val pos = IntArray(2).also { cartao.getLocationOnScreen(it) }
        val t = SystemClock.uptimeMillis()
        a.window.callback.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x, pos[1] + 2f, 0))
        assertEquals("o cartão produziu eventos: $emitidos", emptyList<Emitido>(), emitidos)

        // A loja, no mesmo ecrã e com o cartão aberto, continua a ser medida.
        tocar(botao)
        assertTrue("a primeira interação do ecrã é a da loja", emitidos.any { it.tipo == Tipos.PRIMEIRA_INTERACAO })
        val toque = emitidos.single { it.tipo == Tipos.TOQUE }
        assertEquals(Elemento.chaveDe(botao), toque.elemento)
    }

    @Test
    fun `o comentario do inquerito nao e um campo da aplicacao`() {
        montar()
        val comentario = cartao.campoParaEnsaio()!!
        val chaveDoComentario = Elemento.chaveDe(comentario)

        campo.requestFocus()
        assertTrue("a captura de foco não está a correr", emitidos.any { it.tipo == Tipos.FOCO && it.elemento == Elemento.chaveDe(campo) })

        comentario.requestFocus()
        comentario.setText("O pagamento demorou imenso")
        campo.requestFocus()

        assertFalse(
            "o comentário saiu como campo da aplicação: $emitidos",
            emitidos.any { it.elemento == chaveDoComentario },
        )
        assertEquals("dois focos, e os dois na loja", 2, emitidos.count { it.tipo == Tipos.FOCO })
    }

    @Test
    fun `a submissao da loja nao conta o comentario, e o Enter no comentario nao submete a loja`() {
        montar()
        val comentario = cartao.campoParaEnsaio()!!
        // A ação do teclado (Concluído) é o `submit` da web, e é por ela que a captura
        // submete. O Robolectric não mantém o foco da janela entre um `requestFocus` e
        // uma tecla, e por isso a ação chama-se no próprio campo, que é o que o teclado
        // virtual faz.
        comentario.requestFocus()
        comentario.setText("Muito bom")
        comentario.onEditorAction(EditorInfo.IME_ACTION_DONE)
        a.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertFalse("a ação do teclado no comentário submeteu a loja", emitidos.any { it.tipo == Tipos.SUBMISSAO })

        campo.requestFocus()
        campo.setText("Ana")
        campo.onEditorAction(EditorInfo.IME_ACTION_DONE)
        val submissao = emitidos.single { it.tipo == Tipos.SUBMISSAO }
        val p = submissao.props!!
        assertEquals(
            "o retrato da submissão contou campos que não são da loja: $p", 1,
            (p["campos_preenchidos"] as Int) + (p["campos_vazios"] as Int) + (p["campos_com_erro"] as Int),
        )
        assertFalse(emitidos.any { it.elemento == Elemento.chaveDe(comentario) })
    }

    @Test
    fun `o texto do cartao nunca e uma mensagem da aplicacao`() {
        montar()
        // Um invólucro qualquer marcado como do SDK, com uma vista que a captura
        // reconhece como mensagem pelo nome do recurso, e a mesma vista fora dele.
        class DoSdk(ctx: android.content.Context) : FrameLayout(ctx), VistaDoSdk
        val raiz = LinearLayout(a)
        raiz.addView(DoSdk(a).apply {
            addView(TextView(a).apply { id = android.R.id.message; text = "Pergunta do inquérito que nunca sai" })
        })
        raiz.addView(TextView(a).apply { id = android.R.id.message; text = "Saldo insuficiente para esta operação" })
        // O ecrã novo apaga o travão de 250 ms: a captura acabou de varrer o ecrã no
        // `onActivityResumed`, e sem isto esta varredura nem corria.
        captura.mensagens.ecraNovo()
        captura.mensagens.varrer(raiz)

        val mensagens = emitidos.filter { it.tipo == Tipos.MENSAGEM }
        assertEquals("a da loja sai, e só ela: $mensagens", 1, mensagens.size)
        assertTrue(mensagens.single().extras["message_text_masked"]!!.startsWith("Saldo"))

        // E o cartão verdadeiro, varrido com o ecrã inteiro, não acrescenta nenhuma.
        emitidos.clear()
        captura.mensagens.ecraNovo()
        captura.mensagens.varrer(a.window.decorView)
        assertFalse(emitidos.any { it.extras["message_text_masked"]?.contains("inquérito", ignoreCase = true) == true })
        assertFalse(emitidos.any { it.extras["message_text_masked"]?.contains("fácil") == true })
    }

    @Test
    fun `um defeito do inquerito nao para a captura`() {
        // O pior caso de dentro: as preferências rebentam a meio do gatilho, a
        // configuração chega com lixo, e o desenho do cartão também rebenta. A captura
        // corre por cima de tudo isto e tem de continuar a emitir, porque no `Uxea` é
        // ela que alimenta o inquérito, e não o contrário.
        Seguranca.limpar()
        a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        botao = Button(a).apply { text = "Pagar"; id = View.generateViewId() }
        raiz.addView(botao, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 60))
        a.setContentView(raiz)

        val verdadeiras = InqueritoApoio.prefs()
        val partidas = object : android.content.SharedPreferences by verdadeiras {
            override fun edit(): android.content.SharedPreferences.Editor = throw IllegalStateException("disco cheio")
        }
        val componente = componente(InqueritoApoio.TransporteFalso(), prefs = partidas, fabricar = { _, _, _, _, _ ->
            throw NoSuchMethodError("o desenho rebentou")
        })
        componente.configurar(Configuracao.deJson(org.json.JSONObject(
            """{"inqueritos":{"lista":[${inquerito()},"lixo",{"chave":7}],"tema":{"cor_primaria":[1,2]}}}""",
        )).inqueritos)
        componente.onActivityResumed(a)

        captura = Captura(
            emitir = { tipo, elemento, _, extras, props ->
                emitidos += Emitido(tipo, elemento, extras, props)
                // O mesmo que o `Uxea` faz: o evento primeiro, o inquérito depois.
                componente.observar(evento(tipo = tipo, sessao = "s1"), System.currentTimeMillis())
            },
            definirEcra = {},
        )
        captura.onActivityCreated(a, null)
        captura.onActivityStarted(a)
        captura.onActivityResumed(a)
        andar(200)

        tocar(botao)
        andar(100)
        tocar(botao)
        assertEquals("a captura parou depois do defeito do inquérito", 2, emitidos.count { it.tipo == Tipos.TOQUE })
        assertTrue(emitidos.any { it.tipo == Tipos.ECRA })
        assertTrue("o defeito tem de ficar registado, e não escondido",
            Seguranca.errosInternos().any { it.onde.startsWith("inqueritos.") })
        assertEquals("nada ficou pendurado no ecrã", 1, a.findViewById<FrameLayout>(android.R.id.content).childCount)
    }
}

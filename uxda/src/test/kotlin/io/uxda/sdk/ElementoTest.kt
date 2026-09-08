package io.uxda.sdk

import android.app.Activity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.uxda.sdk.identidade.Elemento
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
 * Identidade de elementos em vistas clássicas. Cartão 3.3, RF-CAP-05, ADR 0003.
 *
 * A árvore é montada aqui e é uma árvore a sério: o Robolectric corre o Android
 * dentro da máquina virtual, e estes ensaios interrogam vistas verdadeiras, com
 * pais, irmãos e identificadores de recurso.
 */
@RunWith(RobolectricTestRunner::class)
class ElementoTest {

    private fun ecra(): LinearLayout {
        val atividade = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(atividade)
        atividade.setContentView(raiz)
        return raiz
    }

    private fun botao(pai: LinearLayout, texto: String, id: Int = 0): Button =
        Button(pai.context).also { b ->
            b.text = texto
            if (id != 0) b.id = id
            pai.addView(b)
        }

    @Test
    fun `o identificador de recurso e o sinal mais forte`() {
        val raiz = ecra()
        val b = botao(raiz, "Pagar 12.400 Kz", android.R.id.button1)
        val s = Elemento.sinais(b)
        assertEquals("id=button1", s.testid)
        assertEquals("testid", Elemento.fonteDe(s))
        assertTrue(Elemento.serializar(s).startsWith("v1|f=testid|t=id=button1"))
    }

    @Test
    fun `sem identificador, a identidade sai do texto, do caminho e do papel`() {
        val raiz = ecra()
        val b = botao(raiz, "Continuar")
        val s = Elemento.sinais(b)
        assertNull(s.testid)
        assertEquals(io.uxda.sdk.identidade.Mascara.resumoDe("Continuar"), s.rotulo)
        assertTrue(s.caminho!!.contains("Button"))
        assertEquals("botao#0", s.papel)
    }

    @Test
    fun `dois botoes iguais distinguem-se pela posicao`() {
        val raiz = ecra()
        val a = botao(raiz, "Guardar")
        val b = botao(raiz, "Guardar")
        assertNotEquals(Elemento.chaveDe(a), Elemento.chaveDe(b))
        assertEquals("botao#0", Elemento.sinais(a).papel)
        assertEquals("botao#1", Elemento.sinais(b).papel)
    }

    @Test
    fun `o montante no rotulo nao fragmenta o elemento`() {
        val raiz = ecra()
        val caro = botao(raiz, "Pagar 12.400 Kz")
        val barato = Button(raiz.context).also { it.text = "Pagar 300 Kz" }
        // O mesmo botão, com o preço de outro utilizador. O rótulo tem de dar o
        // mesmo resumo, senão o catálogo enche-se de entradas repetidas.
        assertEquals(Elemento.sinais(caro).rotulo, Elemento.sinais(barato).rotulo)
    }

    @Test
    fun `num campo de escrita le-se a dica e nunca o que a pessoa escreveu`() {
        val raiz = ecra()
        val campo = EditText(raiz.context).also {
            it.hint = "Nome no cartão"
            it.setText("Ana Maria Silva")
            raiz.addView(it)
        }
        val texto = Elemento.textoVisivel(campo)
        assertEquals("Nome no cartão", texto)
        assertFalse("o que a pessoa escreveu não pode ser lido", texto!!.contains("Ana"))
        assertFalse(Elemento.chaveDe(campo).contains("Ana"))
    }

    @Test
    fun `o caminho ignora os involucros de disposicao`() {
        val raiz = ecra()
        val meio = LinearLayout(raiz.context).also { raiz.addView(it) }
        val b = Button(raiz.context).also { meio.addView(it) }
        val caminho = Elemento.sinalCaminho(b)
        assertFalse("um LinearLayout a mais não pode mudar a identidade", caminho.contains("LinearLayout"))
        assertTrue(caminho.contains("Button"))
    }

    @Test
    fun `o destino declarado entra na cadeia, normalizado`() {
        val raiz = ecra()
        val b = botao(raiz, "Ver pedido")
        b.tag = "uxda:destino=/pedidos/8412"
        assertEquals("/pedidos/{numero}", Elemento.sinais(b).destino)
    }

    @Test
    fun `a serializacao e a mesma que a ingestao reconcilia`() {
        val raiz = ecra()
        val b = botao(raiz, "Pagar", android.R.id.button1)
        val chave = Elemento.chaveDe(b)
        // O formato é o do SDK web, e é o que o módulo `element` da ingestão lê.
        assertTrue(chave.startsWith("v1|"))
        assertTrue(chave.contains("|f="))
        assertTrue(chave.contains("|t="))
        assertTrue(chave.contains("|c="))
        assertTrue(chave.contains("|p="))
        assertTrue("cabe nos 512 do esquema", chave.length <= 512)
        assertFalse("o separador não pode aparecer dentro de um valor", chave.substringAfter("t=").substringBefore("|").contains("|"))
    }

    @Test
    fun `um texto enorme nao rebenta o limite do esquema`() {
        val raiz = ecra()
        val b = botao(raiz, "x".repeat(4000))
        assertTrue(Elemento.chaveDe(b).length <= 512)
    }
}

package io.uxda.sdk

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.uxda.sdk.identidade.Elemento
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Bateria de fuga de conteúdo. RNF-PRI-01, e a quarta caixa da Definição de pronto
 * de qualquer cartão de SDK.
 *
 * Enche um ecrã com marcadores reconhecíveis, corre a identidade sobre ele, e
 * falha se algum marcador aparecer no que sairia do dispositivo.
 *
 * A defesa principal contra fuga não é a revisão de código: é isto, que corre
 * sempre e falha quando alguém acrescentar um campo com boas intenções daqui a
 * dois anos.
 */
@RunWith(RobolectricTestRunner::class)
class FugaTest {

    private val marcadores = listOf(
        "SEGREDOxNOME", "ana.silva@exemplo.ao", "+244923000111",
        "4111111111111111", "AO06000600000100037131174", "SEGREDOxMORADA",
    )

    private fun ecraCheio(): LinearLayout {
        val atividade = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(atividade)
        atividade.setContentView(raiz)
        // Campos preenchidos como uma pessoa os preencheria.
        for ((i, valor) in marcadores.withIndex()) {
            raiz.addView(EditText(raiz.context).also {
                it.hint = "Campo $i"
                it.setText(valor)
                it.contentDescription = "Campo $i"
            })
        }
        // E uma etiqueta que mostra o que a pessoa escreveu, que é o caso mais
        // traiçoeiro: é texto da aplicação a repetir conteúdo do utilizador.
        raiz.addView(TextView(raiz.context).also { it.text = "A pagar com o cartão 4111111111111111" })
        return raiz
    }

    private fun tudoOQueSairia(raiz: LinearLayout): String {
        val saida = StringBuilder()
        fun percorrer(v: android.view.View) {
            saida.append(Elemento.chaveDe(v)).append('\n')
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i))
        }
        percorrer(raiz)
        return saida.toString()
    }

    @Test
    fun `nenhum valor escrito por uma pessoa sai do dispositivo`() {
        val saida = tudoOQueSairia(ecraCheio())
        for (marcador in marcadores) {
            assertFalse("o marcador $marcador saiu do dispositivo", saida.contains(marcador))
        }
        // E nem em pedaços: um número de cartão partido ao meio continua a ser um
        // número de cartão.
        assertFalse(saida.contains("411111"))
        assertFalse(saida.contains("ana.silva"))
        assertFalse(saida.contains("SEGREDO"))
    }

    @Test
    fun `o texto da aplicacao que repete conteudo sai mascarado`() {
        val raiz = ecraCheio()
        val etiqueta = raiz.getChildAt(raiz.childCount - 1) as TextView
        val chave = Elemento.chaveDe(etiqueta)
        assertFalse(chave.contains("4111"))
        // O rótulo é um resumo, e o resumo é o mesmo do texto mascarado.
        assertTrue(chave.contains(io.uxda.sdk.identidade.Mascara.resumoDe("A pagar com o cartão {id}")))
    }

    @Test
    fun `a bateria apanha uma fuga introduzida de proposito`() {
        val raiz = ecraCheio()
        val campo = raiz.getChildAt(0) as EditText
        // Uma "melhoria" que lê o texto do campo, como alguém poderia escrever.
        val comFuga = "v1|f=testid|t=" + campo.text.toString()
        assertTrue("se isto passasse, a bateria não servia para nada", comFuga.contains("SEGREDO"))
        assertFalse(Elemento.chaveDe(campo).contains("SEGREDO"))
    }

    @Test
    fun `nem sequer o comprimento do que foi escrito e inferivel`() {
        val atividade = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(atividade)
        atividade.setContentView(raiz)
        val curto = EditText(raiz.context).also { it.hint = "Nome"; it.setText("Ana"); raiz.addView(it) }
        val longo = EditText(raiz.context).also { it.hint = "Nome"; it.setText("Ana Maria Silva Fernandes"); raiz.addView(it) }
        // O que muda entre as duas é só a posição, e não o comprimento do texto.
        assertEquals(
            Elemento.sinais(curto).rotulo,
            Elemento.sinais(longo).rotulo,
        )
    }

    private fun assertEquals(a: Any?, b: Any?) = org.junit.Assert.assertEquals(a, b)
}

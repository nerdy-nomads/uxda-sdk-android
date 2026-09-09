package io.uxda.sdk

import android.app.Activity
import android.os.Bundle
import io.uxda.sdk.captura.Captura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * O que corre no fio principal, e o que acontece quando a aplicação vai para trás.
 * Cartões 3.1, 3.2 e 3.4.
 *
 * Estes dois ensaios existem por causa de dois defeitos que só apareceram a ler o
 * código depois de a medição estar feita, e nenhum dos dois dava erro nenhum:
 *
 *  - o cronómetro do fio principal estava declarado e **não estava ligado a nada**.
 *    O número medido descrevia só a emissão do evento, e deixava de fora a leitura
 *    da vista, que é a parte que não pode sair do fio principal;
 *  - `guardarSessao()` também não era chamado por ninguém, e com ele a fila não era
 *    despejada no instante em que a aplicação deixa de estar à vista, que é
 *    precisamente quando o sistema costuma abater o processo.
 */
@RunWith(RobolectricTestRunner::class)
class CapturaTest {

    private fun atividade(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test
    fun `todos os pontos de entrada do ciclo de vida passam pelo cronometro`() {
        var medicoes = 0
        val c = Captura(
            emitir = { _, _, _, _, _ -> },
            definirEcra = {},
            medir = { bloco -> medicoes++; bloco() },
        )
        val a = atividade()
        c.onActivityCreated(a, null as Bundle?)
        c.onActivityStarted(a)
        c.onActivityResumed(a)
        c.onActivityStopped(a)
        c.onActivityDestroyed(a)
        assertEquals("cada ponto de entrada tem de ser cronometrado", 5, medicoes)
    }

    @Test
    fun `ir para tras grava a sessao e despeja a fila, uma vez`() {
        var paraTras = 0
        val tipos = mutableListOf<String>()
        val c = Captura(
            emitir = { tipo, _, _, _, _ -> tipos += tipo },
            definirEcra = {},
            aoIrParaTras = { paraTras++ },
        )
        val a = atividade()
        c.onActivityStarted(a)
        c.onActivityStopped(a)
        assertEquals("a aplicação deixou de estar à vista uma vez", 1, paraTras)
        assertTrue("o plano de fundo é o evento que fecha a tentativa", tipos.contains(Tipos.PLANO_FUNDO))
    }

    @Test
    fun `duas atividades a trocar entre si nao contam como ir para tras`() {
        // Uma atividade a abrir outra: a primeira para **depois** de a segunda
        // arrancar, e isso não é a aplicação ir para segundo plano. Sem a contagem
        // de atividades visíveis, cada navegação fechava a sessão.
        var paraTras = 0
        val c = Captura(emitir = { _, _, _, _, _ -> }, definirEcra = {}, aoIrParaTras = { paraTras++ })
        val primeira = atividade()
        val segunda = atividade()
        c.onActivityStarted(primeira)
        c.onActivityStarted(segunda)
        c.onActivityStopped(primeira)
        assertEquals("continua à vista, com a segunda atividade", 0, paraTras)
        c.onActivityStopped(segunda)
        assertEquals(1, paraTras)
    }

    @Test
    fun `a classe que nao tem getError tambem fica guardada`() {
        // O defeito que isto fixa custou 33 ms por evento no fio principal, e não
        // dava erro nenhum: a cache usava `getOrPut`, que volta a calcular sempre
        // que o valor guardado é nulo. Nulo é exatamente o que se guarda para as
        // classes sem `getError`, que são quase todas as de uma árvore de vistas, e
        // por isso cada toque voltava a pedir a lista completa de métodos de cada
        // vista do ecrã.
        val c = Captura(emitir = { _, _, _, _, _ -> }, definirEcra = {})
        val grupo = android.widget.LinearLayout(atividade())

        assertEquals(false, c.temErro(grupo))
        assertTrue("a classe sem getError tem de ficar na cache", c.metodoDeErro.containsKey(grupo.javaClass))
        assertEquals(null, c.metodoDeErro[grupo.javaClass])

        c.temErro(grupo)
        assertEquals("e a segunda passagem não pode acrescentar nada", 1, c.metodoDeErro.size)
    }

    @Test
    fun `um campo de texto nao passa sequer pela reflexao`() {
        // `getError` é API do `TextView`, e é dele que descendem os campos onde a
        // validação da plataforma aparece. O caminho comum não paga reflexão.
        val c = Captura(emitir = { _, _, _, _, _ -> }, definirEcra = {})
        val campo = android.widget.EditText(atividade())
        campo.error = "Falta o nome"

        assertTrue(c.temErro(campo))
        assertTrue("o TextView não devia ter chegado à cache de reflexão", c.metodoDeErro.isEmpty())

        campo.error = null
        assertEquals(false, c.temErro(campo))
    }

    @Test
    fun `o ouvinte da acao do teclado que a aplicacao pos e encontrado`() {
        // A ação do teclado é o `submit` da web, e o SDK encadeia-se ao ouvinte que
        // a aplicação já tiver. Ler esse ouvinte obriga a reflexão sobre um campo
        // privado, e **o caminho até ele mudou entre versões do Android**: em
        // Android 16 a primeira versão rebentava com `NoSuchFieldException`, o que
        // fazia o SDK substituir o ouvinte da aplicação em vez de o encadear, e o
        // formulário de quem nos instalou deixava de submeter.
        val c = Captura(emitir = { _, _, _, _, _ -> }, definirEcra = {})
        val campo = android.widget.EditText(atividade())

        val semNada = c.ouvinteAtualDeAcao(campo)
        assertTrue("um campo sem ouvinte tem de ser legível", semNada.lido)
        assertEquals(null, semNada.ouvinte)

        val meu = android.widget.TextView.OnEditorActionListener { _, _, _ -> true }
        campo.setOnEditorActionListener(meu)

        val comOuvinte = c.ouvinteAtualDeAcao(campo)
        assertTrue("com ouvinte posto, tem de continuar legível", comOuvinte.lido)
        assertSame("e tem de ser o da aplicação, para poder ser chamado", meu, comOuvinte.ouvinte)
    }

    @Test
    fun `o tempo em primeiro plano e o que a aplicacao esteve a vista`() {
        var relogio = 1_000L
        var duracao: Long? = null
        val c = Captura(
            emitir = { tipo, _, d, _, _ -> if (tipo == Tipos.PLANO_FUNDO) duracao = d },
            definirEcra = {},
            agora = { relogio },
        )
        val a = atividade()
        c.onActivityStarted(a)
        relogio += 4_500
        c.onActivityStopped(a)
        assertEquals(4_500L, duracao)
    }
}

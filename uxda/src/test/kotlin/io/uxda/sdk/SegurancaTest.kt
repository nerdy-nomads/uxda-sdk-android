package io.uxda.sdk

import io.uxda.sdk.fila.Armazem
import io.uxda.sdk.fila.Fila
import io.uxda.sdk.fila.Transporte
import io.uxda.sdk.identidade.Elemento
import io.uxda.sdk.identidade.Identidade
import io.uxda.sdk.identidade.Mascara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Injeção sistemática de falhas. Cartão 3.4, RNF-SDK-01.
 *
 * É o único requisito do documento escrito com a frase **não tem exceções**. Em
 * Android isto vale mais do que na web: um erro dentro de um
 * `ActivityLifecycleCallback` ou de um `dispatchTouchEvent` não fica numa consola,
 * **mata o processo**, e quem vê o diálogo de aplicação parada é o utilizador de
 * quem nos instalou.
 *
 * A forma é sempre a mesma: partir uma peça de propósito, correr o SDK por cima
 * dela, e exigir que nada suba.
 */
// Com Robolectric: o `org.json` do `android.jar` dos ensaios é um esboço que
// devolve nulos, e sem um Android a sério por baixo a fila parecia partida quando
// o que estava partido era o ensaio. No telemóvel ela sempre funcionou, e o ensaio
// de dispositivo prova-o.
@RunWith(RobolectricTestRunner::class)
class SegurancaTest {

    @get:Rule
    val pasta = TemporaryFolder()

    @Before
    fun limpar() = Seguranca.limpar()

    @Test
    fun `a barreira apanha Throwable, e nao so Exception`() {
        // Um `NoSuchMethodError` de uma versão diferente do Compose, ou um
        // `StackOverflowError` a percorrer uma árvore, matam o processo na mesma.
        val r = Seguranca.protegido("ensaio", "seguro") { throw NoSuchMethodError("compose mudou") }
        assertEquals("seguro", r)
        assertEquals(1, Seguranca.errosInternos().size)
    }

    @Test
    fun `o registo interno nao cresce sem limite`() {
        repeat(500) { Seguranca.executar("ensaio") { throw IllegalStateException("partido") } }
        assertTrue("o registo cresceu até ${Seguranca.errosInternos().size}", Seguranca.errosInternos().size <= 50)
    }

    @Test
    fun `um ficheiro de fila ilegivel nao trava o arranque`() {
        val f = File(pasta.root, "fila.jsonl")
        f.writeText("isto não é json\n{quebrado\n")
        val a = Armazem(f)
        assertEquals(0, a.quantos())
        a.juntar(evento())
        assertEquals(1, a.quantos())
    }

    @Test
    fun `um disco que recusa escrever nao rebenta a captura`() {
        // Uma pasta onde não se pode escrever é o que acontece quando o
        // armazenamento está cheio ou o sistema revoga permissões.
        val pastaSemPermissao = File(pasta.root, "sem-permissao")
        pastaSemPermissao.mkdirs()
        pastaSemPermissao.setReadOnly()
        val a = Armazem(File(pastaSemPermissao, "fila.jsonl"))
        a.juntar(evento())
        assertEquals("não perdeu o processo por causa do disco", 0, a.quantos())
    }

    @Test
    fun `um transporte que lanca nao sobe para quem chamou`() {
        val a = Armazem(File(pasta.root, "fila.jsonl"))
        a.juntar(evento())
        val t = object : Transporte() {
            override fun enviar(url: String, corpo: String, cabecalhos: Map<String, String>, metodo: String): Resposta =
                throw OutOfMemoryError("sem memória")
        }
        val f = Fila(a, t, "http://x/v1/eventos", { emptyMap() }, { Fila.EstadoRede(true, false, false) })
        assertEquals(0, f.descarregar())
    }

    @Test
    fun `texto absurdo no mascaramento nao rebenta`() {
        val emoji = "😀".repeat(500)
        for (mau in listOf("", " ".repeat(10_000), " ", emoji, "%s %d %n")) {
            Mascara.resumoDe(mau)
        }
    }

    @Test
    fun `identificadores absurdos nao rebentam a pseudonimizacao`() {
        for (mau in listOf("", " ", "@", "+", "a".repeat(5000))) {
            Identidade.pseudonimizar(mau)
        }
    }

    @Test
    fun `uma leitura de vista que rebenta nao mata o processo`() {
        // É o caminho que corre dentro do `dispatchTouchEvent` da anfitriã: se uma
        // exceção subisse daqui, o sistema matava a aplicação de quem nos instalou.
        val chave = Seguranca.protegido("ensaio.vista", "") {
            throw RuntimeException("getTag rebentou")
        }
        assertEquals("", chave)
        assertEquals(1, Seguranca.errosInternos().size)
    }

    @Test
    fun `o leitor de Compose degrada quando a versao muda os nomes`() {
        val r = Seguranca.protegido("compose", null as Elemento.Sinais?) {
            throw NoSuchMethodException("getUnmergedRootSemanticsNode deixou de existir")
        }
        assertEquals(null, r)
        assertTrue(Seguranca.errosInternos().isNotEmpty())
    }

    private fun evento() = Evento(
        eventId = "00000000-0000-4000-8000-000000000001",
        anonymousId = "a", deviceId = "d", sessionId = "s", eventType = Tipos.TOQUE,
        screenKey = "/x", occurredAt = Relogio.iso(System.currentTimeMillis()),
        appVersion = "1", captureLevel = "padrao",
    )
}

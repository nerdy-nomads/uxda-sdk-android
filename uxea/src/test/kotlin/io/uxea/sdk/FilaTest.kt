package io.uxea.sdk

import io.uxea.sdk.fila.Armazem
import io.uxea.sdk.fila.Fila
import io.uxea.sdk.fila.Transporte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A fila em disco. Cartão 3.2, RF-CAP-06, RF-CAP-07, RNF-SDK-05 e RNF-SDK-07.
 *
 * O ensaio que decide o cartão é o da **morte do processo**: em Android não há um
 * separador que se fecha, há um sistema que abate a aplicação sem aviso, e é
 * precisamente aí que a tentativa morre e o evento interessa.
 */
// Com Robolectric: o `org.json` do `android.jar` dos ensaios é um esboço que
// devolve nulos, e sem um Android a sério por baixo a fila parecia partida quando
// o que estava partido era o ensaio. No telemóvel ela sempre funcionou, e o ensaio
// de dispositivo prova-o.
@RunWith(RobolectricTestRunner::class)
class FilaTest {

    @get:Rule val pasta = TemporaryFolder()

    private var relogio = 1_700_000_000_000L

    private fun evento(n: Int, quando: Long = relogio) = Evento(
        eventId = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0'),
        anonymousId = "a", deviceId = "d", sessionId = "s", eventType = Tipos.TOQUE,
        screenKey = "/loja", occurredAt = Relogio.iso(quando), appVersion = "1",
        captureLevel = "padrao",
    )

    private fun armazem(ficheiro: File = File(pasta.root, "fila.jsonl")) = Armazem(ficheiro)

    @Test
    fun `a fila sobrevive a morte do processo`() {
        val f = File(pasta.root, "fila.jsonl")
        val a1 = armazem(f)
        a1.juntar(evento(1))
        a1.juntar(evento(2))
        // Outro `Armazem` sobre o mesmo ficheiro é o que acontece a seguir a o
        // sistema matar a aplicação: o objeto morre, o disco não.
        val a2 = armazem(f)
        assertEquals(2, a2.quantos())
        assertEquals(evento(1).eventId, a2.pendentes()[0].eventId)
    }

    @Test
    fun `uma linha truncada a meio da escrita nao leva a fila atras`() {
        val f = File(pasta.root, "fila.jsonl")
        val a = armazem(f)
        a.juntar(evento(1))
        // O sistema matou o processo a meio de escrever a segunda linha.
        f.appendText("""{"event_id":"0000""")
        val depois = armazem(f)
        assertEquals("a linha inteira sobreviveu, a partida não", 1, depois.quantos())
    }

    @Test
    fun `confirma por identificador, e nao por posicao`() {
        val a = armazem()
        a.juntar(evento(1)); a.juntar(evento(2)); a.juntar(evento(3))
        val lote = a.lote(2, 1_000_000)
        a.juntar(evento(4))
        a.confirmar(lote)
        assertEquals(listOf(evento(3).eventId, evento(4).eventId), a.pendentes().map { it.eventId })
    }

    @Test
    fun `o limite de idade corta o que ja nao descreve nada`() {
        val f = File(pasta.root, "fila.jsonl")
        val a = Armazem(f, maxIdadeMs = 24 * 3600_000L)
        a.juntar(evento(1, relogio - 48 * 3600_000L))
        a.juntar(evento(2, relogio))
        a.aparar(relogio)
        assertEquals(1, a.quantos())
        assertEquals(evento(2).eventId, a.pendentes()[0].eventId)
        assertEquals(1, a.perdidos())
    }

    @Test
    fun `acima do tecto deita fora o mais antigo e conta quantos`() {
        val a = Armazem(File(pasta.root, "fila.jsonl"), maxEventos = 20)
        for (i in 1..30) a.juntar(evento(i))
        a.aparar(relogio)
        assertEquals(20, a.quantos())
        assertEquals(10, a.perdidos())
        assertEquals(evento(11).eventId, a.pendentes()[0].eventId)
    }

    /* ------------------------------------------------------------- envio */

    private class TransporteFalso(var resposta: (String) -> Transporte.Resposta) : Transporte() {
        val pedidos = ArrayList<String>()
        override fun enviar(url: String, corpo: String, cabecalhos: Map<String, String>, metodo: String): Resposta {
            pedidos.add(corpo)
            return resposta(corpo)
        }
    }

    private fun fila(
        a: Armazem,
        t: Transporte,
        rede: Fila.EstadoRede = Fila.EstadoRede(true, false, false),
    ) = Fila(a, t, "http://ingest.local/v1/eventos", { mapOf("X-UXEA-Key" to "uxea_des_t") }, { rede }, { relogio })

    @Test
    fun `vinte e quatro horas sem rede nao perdem um evento`() {
        val ficheiro = File(pasta.root, "fila.jsonl")
        val a = armazem(ficheiro)
        val t = TransporteFalso { Transporte.Resposta(0, "") }
        val f = fila(a, t)

        // Um dia de uso sem rede: um evento de dez em dez minutos.
        for (i in 1..144) {
            f.juntar(evento(i, relogio))
            relogio += 10 * 60_000L
            f.descarregar()
        }
        assertEquals("perdeu eventos com a rede em baixo", 0, a.perdidos())
        assertTrue("devia ter tudo em fila", a.quantos() >= 144)

        // A rede volta, e a aplicação nem sequer foi reaberta: é o mesmo processo.
        t.resposta = { Transporte.Resposta(202, """{"sucesso":true}""") }
        var entregues = 0
        repeat(10) {
            relogio += 10_000
            entregues += f.descarregar()
        }
        assertEquals(144, entregues)
        assertEquals(0, a.quantos())
    }

    @Test
    fun `os erros internos viajam no lote sem a mensagem e saem da conta`() {
        Seguranca.limpar()
        Seguranca.executar("captura.campo") { throw IllegalStateException("o cartão 4111 1111") }
        Seguranca.executar("captura.campo") { throw IllegalStateException("outra vez") }
        Seguranca.executar("captura.toque") { throw NullPointerException() }
        val a = armazem()
        val t = TransporteFalso { Transporte.Resposta(202, """{"sucesso":true}""") }
        val f = fila(a, t)
        f.juntar(evento(1))
        f.descarregar()
        val erros = org.json.JSONObject(t.pedidos[0]).getJSONArray("erros_sdk")
        val porSitio = (0 until erros.length()).associate {
            val e = erros.getJSONObject(it)
            "${e.getString("onde")}|${e.getString("tipo")}" to e.getInt("contagem")
        }
        assertEquals(mapOf("captura.campo|IllegalStateException" to 2, "captura.toque|NullPointerException" to 1), porSitio)
        assertTrue("a mensagem de um erro saiu do dispositivo", !t.pedidos[0].contains("4111"))
        assertTrue("o que o servidor recebeu não sai da conta", Seguranca.errosPorReportar().isEmpty())
    }

    @Test
    fun `os erros por reportar nao crescem sem limite`() {
        Seguranca.limpar()
        for (i in 0 until 50) Seguranca.executar("sitio.$i") { throw RuntimeException("x") }
        assertEquals(20, Seguranca.errosPorReportar().size)
        Seguranca.limpar()
    }

    @Test
    fun `uma recusa definitiva nao fica a repetir para sempre`() {
        val a = armazem()
        val t = TransporteFalso { Transporte.Resposta(401, """{"erro":"chave inválida"}""") }
        val f = fila(a, t)
        f.juntar(evento(1))
        f.descarregar()
        assertEquals("uma chave errada não pode encher o disco do cliente", 0, a.quantos())
        assertTrue(f.estado().ultimoErro.contains("401"))
    }

    @Test
    fun `o recuo cresce e a fila fica intacta`() {
        val a = armazem()
        val t = TransporteFalso { Transporte.Resposta(503, "") }
        val f = fila(a, t)
        f.juntar(evento(1))
        f.descarregar()
        val primeira = f.estado().proximaTentativaEm
        relogio += 60_000
        f.descarregar()
        val segunda = f.estado().proximaTentativaEm
        assertTrue("o recuo tem de crescer", segunda - relogio > primeira - (relogio - 60_000))
        assertEquals("nada se perde enquanto o servidor está em baixo", 1, a.quantos())
    }

    @Test
    fun `em rede medida e com poupanca de bateria abranda em vez de desligar`() {
        val a = armazem()
        val t = TransporteFalso { Transporte.Resposta(202, "{}") }
        for (i in 1..300) a.juntar(evento(i))

        val medida = fila(a, t, Fila.EstadoRede(true, medida = true, poupanca = false))
        val entregues = medida.descarregar()
        assertTrue("com dados contados, o lote é maior e mais raro", entregues > 50)

        // E sem rede não se tenta sequer: gastar rádio a falhar é o pior dos mundos.
        val semRede = fila(a, t, Fila.EstadoRede(false, false, false))
        assertEquals(0, semRede.descarregar())
    }

    @Test
    fun `o servidor em baixo nao faz a aplicacao anfitria falhar`() {
        val a = armazem()
        val t = object : Transporte() {
            override fun enviar(url: String, corpo: String, cabecalhos: Map<String, String>, metodo: String): Resposta =
                throw IllegalStateException("ligação recusada")
        }
        val f = fila(a, t)
        f.juntar(evento(1))
        // Não lança: quem chama isto é o fio de fundo do SDK, e uma exceção aqui
        // subia até ao `Handler` e matava o processo da aplicação anfitriã.
        assertEquals(0, f.descarregar())
        assertEquals(1, a.quantos())
    }
}

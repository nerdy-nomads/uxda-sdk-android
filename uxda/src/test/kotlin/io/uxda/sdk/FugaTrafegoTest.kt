package io.uxda.sdk

import android.app.Activity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.uxda.sdk.captura.Campos
import io.uxda.sdk.captura.Mensagens
import io.uxda.sdk.captura.Progressao
import io.uxda.sdk.captura.Toques
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Bateria de fuga sobre **todo o tráfego de saída do SDK**. Cartão 5.4,
 * RNF-PRI-01 e RF-MSG-04.
 *
 * É a irmã da `FugaTest`, e a diferença é o âmbito: aquela lê os sinais de
 * identidade de uma vista, esta lê **os bytes que sairiam pela rede**, com a
 * captura toda a correr sobre um ecrã cheio de marcadores. O que interessa não é
 * que uma função não devolva um segredo: é que nenhum dos caminhos que existem o
 * ponha dentro de um evento.
 *
 * É o par exato do `src/fuga.test.ts` do SDK web, com os mesmos marcadores e os
 * mesmos ensaios. Uma fuga que só existisse num dos canais era uma fuga na mesma,
 * e um cliente com aplicação e sítio tem os dois.
 */
@RunWith(RobolectricTestRunner::class)
class FugaTrafegoTest {

    /** Os mesmos oito do SDK web, e pela mesma ordem. */
    private val segredos = listOf(
        "005123456LA041",
        "ana.silva@exemplo.ao",
        "+244923000111",
        "4111111111111111",
        "AO06000600000100037131174",
        "Ana Maria da Silva",
        "Rua Amilcar Cabral 42",
        "senha-super-secreta",
    )

    /**
     * O recolhedor: junta o que a captura emite e serializa-o **pelo mesmo caminho
     * que a fila usa** (`Evento.paraJson`). Ler o mapa em memória provava menos:
     * o que sai pela rede é o JSON, e é aí que uma fuga aparece.
     */
    private class Trafego {
        val eventos = mutableListOf<Evento>()

        fun evento(tipo: String, elemento: String?, duracao: Long?, extras: Map<String, String>, props: Map<String, Any>?) {
            eventos += Evento(
                eventId = Ids.uuid(), anonymousId = "a1", deviceId = "d1", sessionId = "s1",
                eventType = tipo, screenKey = "/checkout",
                occurredAt = "2026-09-09T10:00:00.000Z", appVersion = "1.0.0",
                captureLevel = "detalhado", elementKey = elemento, durationMs = duracao,
                messageKey = extras["message_key"], messageKind = extras["message_kind"],
                messageTextMasked = extras["message_text_masked"], properties = props,
            )
        }

        val granular: (String, String?, Long?, Map<String, Any>?) -> Unit =
            { tipo, elemento, duracao, props -> evento(tipo, elemento, duracao, emptyMap(), props) }

        val comExtras: (String, String?, Map<String, String>, Map<String, Any>?) -> Unit =
            { tipo, elemento, extras, props -> evento(tipo, elemento, null, extras, props) }

        /** Tudo o que sairia, tal como sairia. */
        fun bruto(): String = eventos.joinToString("\n") { it.paraJson().toString() }

        fun doTipo(t: String) = eventos.filter { it.eventType == t }
    }

    /**
     * Um ecrã como o de uma aplicação a sério: campos preenchidos por uma pessoa, e
     * mensagens da aplicação com o que ela escreveu interpolado lá dentro.
     */
    private fun correrTarefa(t: Trafego, relogio: () -> Long): LinearLayout {
        val atividade = Robolectric.buildActivity(Activity::class.java).setup().get()
        val raiz = LinearLayout(atividade)
        atividade.setContentView(raiz)

        val campos = Campos(t.granular, relogio, { true }, { false })
        val toques = Toques(t.granular, relogio, { true })
        val progressao = Progressao(t.granular, relogio, { false }) { campos.campoDeAbandono() }
        val mensagens = Mensagens(t.comExtras, relogio, { progressao.passoAtual() })

        progressao.passo("/checkout")

        // Uma pessoa a preencher: entra no campo, escreve, sai.
        val editaveis = segredos.mapIndexed { i, valor ->
            EditText(atividade).also {
                it.hint = "Campo $i"
                it.contentDescription = "Campo $i"
                raiz.addView(it)
                campos.entrar(it)
                it.setText(valor)
                campos.sair(it)
            }
        }
        raiz.addView(Button(atividade).also { it.text = "Pagar"; it.contentDescription = "Pagar" })

        // E a aplicação a responder-lhe, com o que escreveu lá dentro. É o risco
        // crítico do documento, e é o caminho que a fase 5 abriu.
        val avisos = LinearLayout(atividade)
        raiz.addView(avisos)
        for (texto in listOf(
            "O documento 005123456LA041 nao existe",
            "Ja existe conta para ana.silva@exemplo.ao",
            "Ola Ana Maria da Silva, confirme o contacto +244923000111",
            "Enviamos o comprovativo para Rua Amilcar Cabral 42",
            "O valor \"senha-super-secreta\" nao e valido",
            "O IBAN AO06000600000100037131174 nao pertence a este titular",
        )) {
            avisos.addView(TextView(atividade).also { it.id = android.R.id.message; it.text = texto })
        }
        mensagens.varrer(raiz)

        // O `setError` da plataforma, que é o outro sítio por onde texto da
        // aplicação com conteúdo lá dentro sai do dispositivo.
        mensagens.erroDeCampo(editaveis[0], "O documento 005123456LA041 nao existe")

        // E o resto do que a captura sabe fazer.
        toques.semAlvo(10f, 20f, 1080, 2400)
        toques.desativado("id=pagar", 10f, 20f, 1080, 2400)
        toques.anotar("id=pagar")
        toques.fecharRajada()
        campos.aoErrar(editaveis[0], "validacao_nativa")
        campos.aoSubmeter(raiz)
        progressao.espera(1200)
        progressao.terminal(Terminal.ERRO)
        return raiz
    }

    @Test
    fun `nada do que a pessoa escreveu sai do dispositivo`() {
        val t = Trafego()
        var relogio = 1000L
        correrTarefa(t) { relogio += 50; relogio }

        assertTrue("não saiu evento nenhum: a bateria não provava nada", t.eventos.size > 5)
        val bruto = t.bruto()
        for (segredo in segredos) {
            assertFalse("$segredo saiu do dispositivo", bruto.contains(segredo))
        }
    }

    @Test
    fun `a cobertura e das mensagens, das propriedades, das contagens e dos metadados`() {
        // Uma bateria que não veja os quatro é uma bateria que dá conforto a metade
        // do problema. O que se fixa aqui é que os quatro caminhos **existiram**
        // nesta corrida: sem isto, uma alteração que desligasse a captura fazia a
        // bateria passar por não haver nada para encontrar.
        val t = Trafego()
        var relogio = 1000L
        correrTarefa(t) { relogio += 50; relogio }

        assertTrue("sem mensagens", t.doTipo(Tipos.MENSAGEM).isNotEmpty())
        assertTrue("sem agregados de campo", t.doTipo(Tipos.CAMPO).isNotEmpty())
        assertTrue(
            "nenhuma contagem de caracteres: o caminho mais perigoso não foi percorrido",
            t.doTipo(Tipos.CAMPO).any { (it.properties?.get("caracteres_escritos") as? Int ?: 0) > 0 },
        )
        assertTrue("nenhuma chave de elemento", t.eventos.any { !it.elementKey.isNullOrEmpty() })
        assertTrue("nenhum texto de mensagem", t.eventos.any { !it.messageTextMasked.isNullOrEmpty() })
    }

    @Test
    fun `o comprimento do que foi escrito nao e inferivel`() {
        // A contagem de caracteres é um requisito (RF-GRA-12), e é também a porta
        // pela qual alguém conclui que pode capturar conteúdo. A fronteira é esta:
        // conta-se **quantos**, e nunca **quais**.
        val t = Trafego()
        var relogio = 1000L
        correrTarefa(t) { relogio += 50; relogio }
        for (ev in t.doTipo(Tipos.CAMPO)) {
            for ((k, v) in ev.properties.orEmpty()) {
                if (v !is String) continue
                assertTrue("a propriedade $k traz um texto longo: $v", v.length <= 64)
                assertFalse("a propriedade $k traz uma corrida de algarismos: $v", Regex("""\d{5}""").containsMatchIn(v))
            }
        }
    }

    @Test
    fun `a bateria apanha uma fuga introduzida de proposito`() {
        // **É este ensaio que dá valor a todos os outros.** Uma bateria que nunca
        // falhou é uma bateria que ninguém sabe se funciona, e é assim que uma
        // proteção morre: não com um alarme, com um silêncio.
        val t = Trafego()
        // A fuga, escrita como alguém a escreveria a depurar um problema: uma
        // propriedade **da lista de permitidas**, com o valor de um campo lá dentro.
        t.evento(Tipos.PERSONALIZADO, null, null, mapOf("message_key" to "depuracao"), mapOf("segmento" to "Ana Maria da Silva"))
        assertTrue("a bateria não apanharia uma fuga", t.bruto().contains("Ana Maria da Silva"))

        val limpo = Trafego()
        limpo.evento(Tipos.PERSONALIZADO, null, null, mapOf("message_key" to "depuracao"), mapOf("segmento" to "empresas"))
        assertFalse(limpo.bruto().contains("Ana Maria da Silva"))
    }

    @Test
    fun `em volume, quinhentas mensagens com conteudo interpolado, e nada escapa`() {
        // Sintético e em volume, que é o que a caixa do cartão pede. Uma corrida com
        // seis mensagens prova o caminho; quinhentas, com valores diferentes em cada
        // uma, provam que não há um recanto do mascaramento que só falhe para uma
        // forma de escrever o montante.
        val atividade = Robolectric.buildActivity(Activity::class.java).setup().get()
        val t = Trafego()
        var relogio = 1000L
        val mensagens = Mensagens(t.comExtras, { relogio })

        val moldes = listOf<(String) -> String>(
            { "O saldo de $it Kz e insuficiente" },
            { "O documento $it nao existe" },
            { "Ja existe conta para $it" },
            { "O valor \"$it\" nao e valido" },
            { "Confirme o contacto $it" },
        )
        val valores = ArrayList<String>(500)
        for (i in 0 until 500) {
            val v = when (i % 5) {
                0 -> "${12400 + i}"
                1 -> "${1000 + i},50"
                2 -> "00512" + i.toString().padStart(4, '0') + "LA041"
                3 -> "pessoa$i@exemplo.ao"
                else -> "+2449230001" + (i % 100).toString().padStart(2, '0')
            }
            valores += v
            val raiz = LinearLayout(atividade).also { r ->
                r.addView(TextView(atividade).also { it.id = android.R.id.message; it.text = moldes[i % 5](v) })
            }
            // Cada mensagem passa a janela de repetição, senão a segunda em diante
            // era descartada e a bateria corria sobre uma só.
            relogio += 2000
            mensagens.varrer(raiz)
        }

        val textos = t.doTipo(Tipos.MENSAGEM).mapNotNull { it.messageTextMasked }
        assertTrue("só saíram ${textos.size} mensagens: a bateria correu sobre pouco", textos.size >= 480)
        for (texto in textos) {
            assertFalse("um texto saiu com uma corrida de algarismos: $texto", Regex("""\d{5}""").containsMatchIn(texto))
            assertFalse("um texto saiu com um endereço de correio: $texto", texto.contains("@"))
        }
        // E os valores que não se confundem com nada verificam-se no tráfego inteiro.
        val bruto = t.bruto()
        val distintivos = valores.filter { Regex("""[A-Za-z@+]""").containsMatchIn(it) }
        val escaparam = distintivos.filter { bruto.contains(it) }
        assertEquals("escaparam ${escaparam.size} valores: ${escaparam.take(5)}", emptyList<String>(), escaparam)
        println("  5.4 volume Android: ${textos.size} mensagens verificadas, ${valores.size} valores interpolados, ${distintivos.size} distintivos, 0 fugas")
    }
}

package io.uxda.sdk

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections

/**
 * A bateria de fuga sobre **toda a captura** do SDK Android, com o SDK inteiro a correr.
 * Cartão 18.2, `RNF-PRI-01`, `RNF-PRI-02`. O par do `src/fuga-total.test.ts` da web.
 *
 * O SDK arranca contra um servidor HTTP **a sério**, aqui dentro, que serve a
 * configuração (nível detalhado, rastreio individual ligado) e guarda os corpos que
 * recebe: o que se procura é o que sai pela rede, pelo transporte do SDK, e não o que
 * uma função devolve. E tem a mesma guarda de cobertura: todos os tipos de evento do
 * esquema têm de aparecer, ou a bateria cai.
 */
@RunWith(RobolectricTestRunner::class)
class FugaTotalTest {

    companion object {
        /** Entre ensaios: o JUnit faz uma instância por método, e o objeto `Uxda` é um só. */
        private var corridas = 0
    }

    private val segredos = listOf(
        "005123456LA041", "ana.silva@exemplo.ao", "+244923000111", "4111111111111111",
        "AO06000600000100037131174", "Ana Maria da Silva", "Rua Amilcar Cabral 42", "senha-super-secreta",
    )

    private val recebidos = Collections.synchronizedList(ArrayList<String>())
    private var servidor: ServerSocket? = null
    private var config = """{"amostragem":1,"nivel":"detalhado","amostragem_detalhado":1,"rastreio_individual":true,"versao":1}"""

    /**
     * Um servidor HTTP mínimo, sobre um `ServerSocket`: lê a linha do pedido, os
     * cabeçalhos e o corpo, e responde. O transporte do SDK fala com ele como falaria com
     * a ingestão, e é por isso que o que aqui se lê é o que sairia pela rede.
     */
    private fun arrancarServidor(): Int {
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        servidor = s
        Thread {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Throwable) { break }
                Thread {
                    c.use { sock ->
                        val ent = sock.getInputStream().buffered()
                        fun linha(): String {
                            val sb = StringBuilder()
                            while (true) {
                                val b = ent.read()
                                if (b < 0 || b == '\n'.code) break
                                if (b != '\r'.code) sb.append(b.toChar())
                            }
                            return sb.toString()
                        }
                        val pedido = linha()
                        var tamanho = 0
                        while (true) {
                            val h = linha()
                            if (h.isEmpty()) break
                            if (h.lowercase().startsWith("content-length:")) tamanho = h.substringAfter(":").trim().toInt()
                        }
                        val corpo = ByteArray(tamanho)
                        var lidos = 0
                        while (lidos < tamanho) { val n = ent.read(corpo, lidos, tamanho - lidos); if (n < 0) break; lidos += n }
                        val caminho = pedido.split(" ").getOrElse(1) { "/" }
                        val (estado, resposta) = when {
                            caminho.startsWith("/v1/config") -> 200 to """{"sucesso":true,"dados":$config}"""
                            caminho.startsWith("/v1/eventos") -> {
                                recebidos.add(String(corpo, Charsets.UTF_8))
                                202 to """{"sucesso":true,"dados":{"aceites":1}}"""
                            }
                            else -> 404 to "{}"
                        }
                        val b = resposta.toByteArray()
                        sock.getOutputStream().apply {
                            write("HTTP/1.1 $estado OK\r\nContent-Type: application/json\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(b); flush()
                        }
                    }
                }.start()
            }
        }.apply { isDaemon = true }.start()
        return s.localPort
    }

    @After
    fun fechar() {
        Uxda.parar()
        servidor?.close()
    }

    /** O mesmo detetor da web: a forma escrita, minúsculas, codificada num endereço, e só os algarismos. */
    private fun fugas(bruto: String, lista: List<String> = segredos): List<String> {
        val dec = try { java.net.URLDecoder.decode(bruto.replace(Regex("%(?![0-9a-fA-F]{2})"), "%25"), "UTF-8") } catch (_: Throwable) { bruto }
        val textos = listOf(bruto, dec, bruto.lowercase(), dec.lowercase())
        val digitos = dec.filter { it.isDigit() }
        return lista.filter { s ->
            val formas = listOf(s, s.lowercase(), java.net.URLEncoder.encode(s, "UTF-8"))
            formas.any { f -> textos.any { it.contains(f) } } ||
                s.filter { it.isDigit() }.let { d -> d.length >= 9 && digitos.contains(d) }
        }
    }

    private fun esperar(condicao: () -> Boolean) {
        val fim = System.currentTimeMillis() + 15_000
        while (!condicao() && System.currentTimeMillis() < fim) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun tocar(a: Activity, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        a.window.callback.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        a.window.callback.dispatchTouchEvent(MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun corrida(): String {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val porta = arrancarServidor()
        Uxda.iniciar(app, Opcoes(chave = "uxda_des_teste", servidor = "http://127.0.0.1:$porta", versaoApp = "1.0.0"))
        esperar { Uxda.diagnostico()["origemDaConfiguracao"] == "servidor" }

        val ctl = Robolectric.buildActivity(Activity::class.java).setup()
        val a = ctl.get()
        val raiz = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val rolo = ScrollView(a).apply { addView(raiz) }
        a.setContentView(rolo)
        val nome = EditText(a).apply { tag = "titular-${segredos[1]}"; hint = "Nome" }
        val bi = EditText(a).apply { tag = "bi_${segredos[0]}"; hint = "Documento" }
        val pagar = Button(a).apply { text = "Pagar a ${segredos[5]}"; tag = "pagar" }
        val desligado = Button(a).apply { text = "Confirmar ${segredos[3]}"; isEnabled = false }
        val aviso = TextView(a).apply { id = android.R.id.message }
        listOf(nome, bi, pagar, desligado, aviso).forEach(raiz::addView)
        repeat(30) { raiz.addView(TextView(a).apply { text = "linha $it"; height = 80 }) }
        rolo.measure(1080, 2400); rolo.layout(0, 0, 1080, 2400)
        ctl.resume()
        shadowOf(Looper.getMainLooper()).idle()

        // Uma pessoa a preencher.
        for ((campo, valor) in listOf(nome to segredos[5], bi to segredos[0])) {
            campo.requestFocus(); shadowOf(Looper.getMainLooper()).idle()
            campo.setText(valor)
        }
        // O erro de validação da aplicação, com o que a pessoa escreveu lá dentro, e a
        // submissão pelo teclado, com o foco no campo.
        bi.error = "O documento ${segredos[0]} não existe"
        shadowOf(Looper.getMainLooper()).idle()
        // Pela ação do teclado no campo, que é o que um telemóvel usa: o `currentFocus`
        // da atividade não existe no Robolectric, e o caminho da tecla Enter fica para o
        // ensaio no dispositivo.
        bi.requestFocus(); shadowOf(Looper.getMainLooper()).idle()
        bi.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
        shadowOf(Looper.getMainLooper()).idle()
        pagar.requestFocus(); shadowOf(Looper.getMainLooper()).idle()
        // Toques: no botão, várias vezes seguidas, no desativado, e no vazio.
        val loc = IntArray(2)
        pagar.getLocationOnScreen(loc)
        repeat(4) { tocar(a, loc[0] + 5f, loc[1] + 5f) }
        desligado.getLocationOnScreen(loc); tocar(a, loc[0] + 5f, loc[1] + 5f)
        tocar(a, 1000f, 2300f)
        // A aplicação ocupada: um toque durante um pedido, e a espera.
        Uxda.pedidoComecou()
        pagar.getLocationOnScreen(loc); tocar(a, loc[0] + 6f, loc[1] + 6f)
        Uxda.pedidoAcabou(900)
        // Uma mensagem da aplicação, com o que a pessoa escreveu lá dentro.
        aviso.text = "O IBAN ${segredos[4]} de ${segredos[5]} foi recusado"
        shadowOf(Looper.getMainLooper()).idle()
        // Deslocamento.
        rolo.scrollTo(0, 1500); shadowOf(Looper.getMainLooper()).idle()
        // A API inteira, com o que um programador da instituição lá poria a depurar.
        Uxda.ecra("detalhe ${segredos[1]}")
        Uxda.passo("confirmar_${segredos[0]}")
        Uxda.track("pagou_${segredos[4]}", mapOf("segmento" to segredos[5], "canal" to segredos[6], "nota" to segredos[7], "valor_monetario" to 12400))
        Uxda.mensagem("recusado_${segredos[3]}", "erro", "pagamento ${segredos[1]}")
        Uxda.erroTecnico("resposta_ilegivel", segredos[2])
        // Um código diferente em cada corrida: o SDK agrupa a mesma falha durante três
        // segundos, e o objeto `Uxda` é o mesmo de um ensaio para o outro.
        Uxda.erroDeRede("https://api.exemplo.ao/clientes/${segredos[1]}/cartoes/${segredos[3]}?nif=${segredos[0]}", 500 + (corridas++))
        Uxda.erroDeRede("https://api.exemplo.ao/pagar/${segredos[2]}", 0, true)
        Uxda.identificar(segredos[5])
        Uxda.terminal(Terminal.ERRO)
        a.window.callback.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        // Segundo plano e regresso, que é o que faz nascer o ambiente.
        ctl.pause().stop(); shadowOf(Looper.getMainLooper()).idle()
        ctl.restart().start().resume(); shadowOf(Looper.getMainLooper()).idle()
        ctl.pause().stop(); shadowOf(Looper.getMainLooper()).idle()
        Uxda.descarregar()
        esperar { recebidos.joinToString("").contains("\"plano_fundo\"") && recebidos.joinToString("").contains("\"personalizado\"") }
        Thread.sleep(300)
        return recebidos.joinToString("\n")
    }

    private fun tiposDoEsquema(): List<String> {
        val s = JSONObject(javaClass.classLoader!!.getResource("schema.json").readText())
        val campos = s.getJSONArray("campos")
        for (i in 0 until campos.length()) {
            val c = campos.getJSONObject(i)
            if (c.getString("nome") == "event_type") {
                val v = c.getJSONArray("valores")
                return (0 until v.length()).map { v.getString(it) }
            }
        }
        return emptyList()
    }

    private fun tiposRecebidos(bruto: String): Set<String> =
        Regex("\"event_type\":\"([a-z_]+)\"").findAll(bruto).map { it.groupValues[1] }.toSet()

    @Test
    fun `nenhum segredo sai por caminho nenhum, e todos os tipos de evento foram percorridos`() {
        val bruto = corrida()
        assertTrue("quase nada saiu: a bateria não provava nada", tiposRecebidos(bruto).size > 5)
        assertEquals("saiu do dispositivo", emptyList<String>(), fugas(bruto))
        val faltam = tiposDoEsquema().filter { it !in tiposRecebidos(bruto) }
        assertEquals("tipos de evento sem cobertura nesta bateria", emptyList<String>(), faltam)
    }

    @Test
    fun `a bateria apanha uma fuga introduzida de proposito fora das mensagens`() {
        config = """{"amostragem":1,"nivel":"padrao","versao":1,"exposicao":{"propriedades":["segmento"]}}"""
        val bruto = corrida()
        assertEquals(listOf("Ana Maria da Silva"), fugas(bruto))
    }

    @Test
    fun `o detetor apanha as formas codificadas`() {
        assertEquals(listOf("ana.silva@exemplo.ao"), fugas("""{"u":"ana.silva%40exemplo.ao"}"""))
        assertEquals(listOf("4111111111111111"), fugas("""{"c":"4111 1111 1111 1111"}"""))
        assertEquals(emptyList<String>(), fugas("""{"x":"{email} {id}"}"""))
    }

    @Test
    fun `em volume, trezentas pessoas com dados diferentes em todos os caminhos, e nada escapa`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val porta = arrancarServidor()
        Uxda.iniciar(app, Opcoes(chave = "uxda_des_teste", servidor = "http://127.0.0.1:$porta", versaoApp = "1.0.0"))
        esperar { Uxda.diagnostico()["origemDaConfiguracao"] == "servidor" }
        val ctl = Robolectric.buildActivity(Activity::class.java).setup()
        val a = ctl.get()
        val raiz = LinearLayout(a)
        a.setContentView(raiz)
        val campo = EditText(a).apply { hint = "Nome" }
        raiz.addView(campo)
        ctl.resume(); shadowOf(Looper.getMainLooper()).idle()
        val r = java.util.Random(182)
        val nomes = listOf("Ana", "Bruno", "Carla", "Domingos", "Esperança", "Fernando", "Graça", "Helder")
        val apelidos = listOf("Silva", "Santos", "Ferreira", "Costa", "Neto", "Miranda")
        val pessoais = ArrayList<String>()
        val n = 300
        for (i in 0 until n) {
            val nome = "${nomes[r.nextInt(nomes.size)]} ${apelidos[r.nextInt(apelidos.size)]} ${apelidos[r.nextInt(apelidos.size)]}"
            val nif = (100_000_000 + r.nextInt(899_999_999)).toString()
            val correio = "pessoa$i.${nif.take(4)}@exemplo.ao"
            val cartao = "4" + (0 until 15).joinToString("") { r.nextInt(10).toString() }
            pessoais += listOf(nome, nif, correio, cartao)
            campo.requestFocus(); campo.setText(nome); campo.clearFocus()
            Uxda.track("evento_$nif", mapOf("segmento" to nome, "campanha" to correio))
            Uxda.ecra("/clientes/$correio")
            Uxda.mensagem("erro_$cartao", "erro", nome)
            Uxda.erroDeRede("https://api.exemplo.ao/contas/$nif/cartoes/$cartao?email=$correio", 500)
            // A fila guarda quinhentos eventos e envia lotes de duzentos: numa sessão
            // real esvazia-se pelo caminho, e aqui também.
            if (i % 30 == 29) {
                shadowOf(Looper.getMainLooper()).idle()
                val antes = recebidos.size
                Uxda.descarregar()
                esperar { recebidos.size > antes }
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        Uxda.descarregar()
        repeat(10) {
            val antes = recebidos.size
            Uxda.descarregar()
            esperar { recebidos.size > antes }
        }
        val bruto = recebidos.joinToString("\n")
        val eventos = Regex("\"event_type\"").findAll(bruto).count()
        val achadas = fugas(bruto, pessoais)
        println("  18.2 volume Android: $n pessoas, ${pessoais.size} valores pessoais distintos, $eventos eventos em ${recebidos.size} pedidos, ${achadas.size} fugas")
        assertTrue("saíram menos eventos do que pessoas: a corrida não provava nada", eventos > n)
        assertEquals(emptyList<String>(), achadas.take(5))
    }
}

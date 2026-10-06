package io.uxea.sdk

import android.app.Activity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import io.uxea.sdk.captura.Campos
import io.uxea.sdk.captura.Deslocamento
import io.uxea.sdk.captura.Progressao
import io.uxea.sdk.captura.Toques
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Captura granular. Cartões 4.1 a 4.4, RF-GRA-01 a RF-GRA-25.
 *
 * O que estes ensaios protegem é o que **nenhuma ferramenta de funis vê**: um
 * funil vê os passos que aconteceram, e um toque numa zona morta não é um passo.
 */
@RunWith(RobolectricTestRunner::class)
class GranularTest {

    private class Saida {
        val eventos = mutableListOf<Triple<String, Long?, Map<String, Any>?>>()
        val emitir: (String, String?, Long?, Map<String, Any>?) -> Unit =
            { tipo, _, duracao, props -> eventos += Triple(tipo, duracao, props) }
        fun doTipo(t: String) = eventos.filter { it.first == t }
    }

    private fun atividade(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    /* --------------------------------------------------------------- 4.1 */

    @Test
    fun `os tres casos aparecem separados, e nao num contador de toques falhados`() {
        val s = Saida()
        var relogio = 1000L
        val t = Toques(s.emitir, { relogio }, { false })

        t.semAlvo(50f, 900f, 1080, 2400)
        t.desativado("id=confirmar", null, 10f, 10f, 1080, 2400)
        repeat(3) { t.anotar("id=pagar"); relogio += 200 }
        t.fecharRajada()

        assertEquals(1, s.doTipo(Tipos.TOQUE_SEM_ALVO).size)
        assertEquals(1, s.doTipo(Tipos.TOQUE_DESATIVADO).size)
        assertEquals(1, s.doTipo(Tipos.TOQUE_REPETIDO).size)

        val repetido = s.doTipo(Tipos.TOQUE_REPETIDO).first()
        assertEquals(3, repetido.third!!["repeticoes"])
        assertTrue("o intervalo entre insistências", (repetido.third!!["intervalo_ms"] as Long) > 0)
    }

    @Test
    fun `um toque que funciona traz a posicao, e nao so os que falham`() {
        // **É o defeito que a loja de ensaio da web apanhou no browser**, e que a
        // paridade obrigou a corrigir dos dois lados: os três casos em que nada
        // acontece emitem-se no `Toques`, e o toque normal é emitido pela
        // `Captura`. As coordenadas saíam nos toques mortos e não nos que
        // funcionam, e o mapa de calor do cartão 9.2 ficava a desenhar só as
        // falhas, que é o oposto de um mapa de calor.
        val s = Saida()
        val t = Toques(s.emitir, { 0 }, { true }, { true })
        val botao = android.widget.Button(atividade()).also {
            it.layout(0, 0, 200, 100)
        }
        val onde = t.posicaoDoToque(botao, 540f, 1200f, 1080, 2400)
        assertEquals("em percentagem da janela", 50, onde["toque_x"])
        assertEquals(50, onde["toque_y"])
        assertEquals(1080, onde["visor_largura"])
        assertEquals(1, onde["ordem_na_sequencia"])
        assertEquals("3x5", onde["zona"])
        assertTrue("a caixa do alvo, para o esquema do ecrã", onde.containsKey("alvo_caixa"))

        // Sem rastreio individual, a mesma chamada devolve só a zona: desligar tem
        // de parar de recolher, e não só de mostrar.
        val sem = Saida()
        val semRastreio = Toques(sem.emitir, { 0 }, { true }, { false })
            .posicaoDoToque(botao, 540f, 1200f, 1080, 2400)
        assertEquals("3x5", semRastreio["zona"])
        assertEquals(null, semRastreio["toque_x"])
        assertEquals(null, semRastreio["alvo_caixa"])
    }

    @Test
    fun `a ordem da interacao nao salta quando o mesmo toque produz dois eventos`() {
        // Um toque numa aplicação ocupada produz **dois** eventos: o
        // `toque_em_carregamento` e o `toque`. Com a posição calculada duas vezes,
        // o contador da ordem avançava duas, e a sequência do RF-IND-01 ficava com
        // buracos precisamente nos ecrãs lentos, que são os que alguém vai lá ver.
        val s = Saida()
        val t = Toques(s.emitir, { 0 }, { true }, { true })
        val onde = t.posicaoDoToque(null, 100f, 100f, 1080, 2400)
        t.emCarregamento("id=pagar", onde)
        val emCarregamento = s.doTipo(Tipos.TOQUE_EM_CARREGAMENTO).first().third!!
        assertEquals(1, emCarregamento["ordem_na_sequencia"])

        val segundo = t.posicaoDoToque(null, 100f, 100f, 1080, 2400)
        assertEquals("o gesto seguinte, e não o terceiro", 2, segundo["ordem_na_sequencia"])
    }

    @Test
    fun `a zona vai sempre e as coordenadas precisam das duas condicoes`() {
        val padrao = Saida()
        Toques(padrao.emitir, { 0 }, { false }, { true }).semAlvo(540f, 1200f, 1080, 2400)
        val p = padrao.doTipo(Tipos.TOQUE_SEM_ALVO).first().third!!
        assertEquals("3x5", p["zona"])
        assertEquals(null, p["toque_x"])

        // **O nível detalhado sozinho não chega** (cartão 9.1). Ele diz quanta
        // granularidade se capta; o rastreio individual diz se é legítimo seguir
        // uma pessoa, e são decisões diferentes de quem opera (RF-IND-09).
        val semRastreio = Saida()
        Toques(semRastreio.emitir, { 0 }, { true }, { false }).semAlvo(540f, 1200f, 1080, 2400)
        val sr = semRastreio.doTipo(Tipos.TOQUE_SEM_ALVO).first().third!!
        assertEquals("a zona continua a sair: agrupa e não localiza ninguém", "3x5", sr["zona"])
        assertEquals(null, sr["toque_x"])

        val detalhado = Saida()
        Toques(detalhado.emitir, { 0 }, { true }, { true }).semAlvo(540f, 1200f, 1080, 2400)
        val d = detalhado.doTipo(Tipos.TOQUE_SEM_ALVO).first().third!!
        assertEquals("em percentagem da janela, para caber no mesmo mapa que a web", 50, d["toque_x"])
        assertEquals(50, d["toque_y"])
        // As dimensões do visor vão junto: sem elas, duas percentagens iguais em
        // ecrãs de tamanhos diferentes são o mesmo ponto no mapa e coisas
        // diferentes na vida (RF-IND-02).
        assertEquals(1080, d["visor_largura"])
        assertEquals(2400, d["visor_altura"])
        assertEquals("a primeira interação deste ecrã", 1, d["ordem_na_sequencia"])
    }

    @Test
    fun `9_1 a profundidade conta o fundo do visor, e nao o topo`() {
        // A mesma conta do SDK web, e é isso que faz os dois mapas comparáveis.
        // Com o topo, um ecrã que cabe inteiro dava 0%, que é o oposto do que
        // aconteceu: foi visto todo sem ninguém ter de descer.
        val d = Deslocamento({ _: String, _: String?, _: Long?, _: Map<String, Any>? -> }, { 0 }, { true }, { true })
        assertEquals(100, d.profundidadeDe(0, 800, 800))
        assertEquals(50, d.profundidadeDe(0, 800, 1600))
        assertEquals(100, d.profundidadeDe(800, 800, 1600))
        assertEquals(75, d.profundidadeDe(400, 800, 1600))
        assertEquals("o salto elástico não inventa página", 100, d.profundidadeDe(2000, 800, 1600))
    }

    @Test
    fun `9_1 a ordem da interacao conta-se por ecra, e nao por sessao`() {
        val s = Saida()
        val t = Toques(s.emitir, { 0 }, { true }, { true })
        t.semAlvo(10f, 10f, 1080, 2400)
        t.semAlvo(20f, 20f, 1080, 2400)
        t.ecraNovo()
        t.semAlvo(30f, 30f, 1080, 2400)
        val ordens = s.doTipo(Tipos.TOQUE_SEM_ALVO).map { it.third!!["ordem_na_sequencia"] }
        assertEquals(listOf(1, 2, 1), ordens)
    }

    @Test
    fun `o tempo ate a primeira interacao conta-se por ecra`() {
        val s = Saida()
        var relogio = 0L
        val t = Toques(s.emitir, { relogio }, { false })
        relogio = 1800
        t.primeiraInteracao()
        t.primeiraInteracao()
        assertEquals("uma por ecrã, e não uma por toque", 1, s.doTipo(Tipos.PRIMEIRA_INTERACAO).size)
        assertEquals(1800L, s.doTipo(Tipos.PRIMEIRA_INTERACAO).first().second)

        t.ecraNovo()
        relogio = 2500
        t.primeiraInteracao()
        assertEquals(2, s.doTipo(Tipos.PRIMEIRA_INTERACAO).size)
    }

    /* --------------------------------------------------------------- 4.2 */

    private fun formulario(): Pair<Activity, List<EditText>> {
        val a = atividade()
        val raiz = LinearLayout(a)
        val campos = (1..3).map { i ->
            EditText(a).also { it.hint = "Campo $i"; raiz.addView(it) }
        }
        raiz.addView(Button(a).also { it.text = "Pagar" })
        a.setContentView(raiz)
        return a to campos
    }

    @Test
    fun `um evento por campo, com hesitacao e contagens, e nunca os caracteres`() {
        val s = Saida()
        var relogio = 0L
        val (_, campos) = formulario()
        val c = Campos(s.emitir, { relogio }, { false }, { false })

        c.entrar(campos[0])
        relogio = 900
        campos[0].setText("Ana")
        relogio = 4000
        c.sair(campos[0])

        val evs = s.doTipo(Tipos.CAMPO)
        assertEquals("um evento por campo, e um só", 1, evs.size)
        val p = evs.first().third!!
        assertEquals(900L, p["hesitacao_ms"])
        assertEquals(3, p["caracteres_escritos"])
        assertEquals(0, p["caracteres_apagados"])
        assertEquals(4000L, evs.first().second)

        // E o que ela escreveu não está em lado nenhum.
        assertTrue("o conteúdo do campo saiu", !s.eventos.toString().contains("Ana"))
    }

    @Test
    fun `colagem distingue-se de introducao manual`() {
        val s = Saida()
        val (_, campos) = formulario()
        val c = Campos(s.emitir, { 0 }, { false }, { false })

        c.entrar(campos[0])
        campos[0].append("A")
        c.sair(campos[0])

        c.entrar(campos[1])
        campos[1].setText("4111111111111111")
        c.sair(campos[1])

        val evs = s.doTipo(Tipos.CAMPO)
        assertEquals("manual", evs[0].third!!["origem"])
        assertEquals("colagem", evs[1].third!!["origem"])
        assertEquals(16, evs[1].third!!["caracteres_escritos"])
    }

    @Test
    fun `apagar conta, e e dos melhores sinais de dificuldade que existem`() {
        val s = Saida()
        val (_, campos) = formulario()
        val c = Campos(s.emitir, { 0 }, { false }, { false })
        c.entrar(campos[0])
        campos[0].setText("12345")
        campos[0].setText("123")
        c.sair(campos[0])
        val p = s.doTipo(Tipos.CAMPO).first().third!!
        assertEquals(5 + 3, p["caracteres_escritos"])
        assertEquals(5, p["caracteres_apagados"])
    }

    @Test
    fun `regressos, ordem efetiva e ordem prevista`() {
        val s = Saida()
        val (_, campos) = formulario()
        val c = Campos(s.emitir, { 0 }, { false }, { false })

        c.entrar(campos[2]); c.sair(campos[2])
        c.entrar(campos[0]); c.sair(campos[0])
        c.entrar(campos[2]); c.sair(campos[2])

        val ultimo = s.doTipo(Tipos.CAMPO).last().third!!
        assertEquals("foi o primeiro a ser preenchido", 1, ultimo["ordem"])
        assertEquals("mas o formulário previa que fosse o terceiro", 3, ultimo["ordem_prevista"])
        assertEquals("voltou uma vez ao mesmo campo", 1, ultimo["regressos"])
    }

    @Test
    fun `um campo visitado e deixado vazio nao e um campo nunca visitado`() {
        val s = Saida()
        val (a, campos) = formulario()
        val c = Campos(s.emitir, { 0 }, { false }, { false })
        c.entrar(campos[0])
        c.sair(campos[0])
        assertEquals(true, s.doTipo(Tipos.CAMPO).first().third!!["visitado_vazio"])

        // Na submissão saem os três, incluindo os dois onde ninguém tocou.
        campos[1].setText("Ana")
        val (preenchidos, vazios, _) = c.aoSubmeter(a.window.decorView)
        assertEquals(1, preenchidos)
        assertEquals(2, vazios)
        val naSubmissao = s.doTipo(Tipos.CAMPO).filter { it.third?.get("fase") == "submissao" }
        assertEquals("um retrato por campo do formulário", 3, naSubmissao.size)
    }

    /* --------------------------------------------------------------- 4.4 */

    @Test
    fun `cada transicao de passo traz o passo anterior e o tempo que ele levou`() {
        val s = Saida()
        var relogio = 0L
        val p = Progressao(s.emitir, { relogio }, { false }, { "" })
        p.passo("/pagamento")
        relogio = 2500
        p.passo("/confirmar")

        val passos = s.doTipo(Tipos.PASSO)
        assertEquals(2, passos.size)
        assertEquals("/confirmar", passos[1].third!!["passo"])
        assertEquals("/pagamento", passos[1].third!!["passo_anterior"])
        assertEquals(2500L, passos[1].second)
    }

    @Test
    fun `o evento terminal e inequivoco, e sao quatro estados`() {
        for (estado in listOf(Terminal.SUCESSO, Terminal.ERRO, Terminal.ABANDONADO, Terminal.EXPIRADO)) {
            val s = Saida()
            val p = Progressao(s.emitir, { 0 }, { false }, { "id=cartao" })
            p.terminal(estado)
            p.terminal(estado)
            assertEquals("$estado: um terminal, e um só", 1, s.doTipo(Tipos.TERMINAL).size)
            assertEquals(estado, s.doTipo(Tipos.TERMINAL).first().third!!["estado"])
        }
    }

    @Test
    fun `sair com trabalho a meio e um abandono, com o campo onde ela estava`() {
        // O documento chama a isto a informação mais acionável do conjunto: não é
        // o passo que provoca abandono, é quase sempre um campo concreto dentro
        // dele.
        val s = Saida()
        val p = Progressao(s.emitir, { 0 }, { false }, { "id=cartao#0d29" })
        p.passo("/pagamento")
        p.esconder()

        val t = s.doTipo(Tipos.TERMINAL).first().third!!
        assertEquals(Terminal.ABANDONADO, t["estado"])
        assertEquals("id=cartao#0d29", t["campo_abandono"])
    }

    @Test
    fun `a duracao de cada ausencia para segundo plano e registada`() {
        val s = Saida()
        var relogio = 0L
        val p = Progressao(s.emitir, { relogio }, { false }, { "" })
        p.esconder()
        relogio = 7000
        p.mostrar()
        val regresso = s.doTipo(Tipos.AMBIENTE).first { it.third?.get("mudanca") == "primeiro_plano" }
        assertEquals(7000L, regresso.second)
    }

    @Test
    fun `a espera imposta pelo sistema sai num evento proprio`() {
        // Somada ao tempo do passo, ninguém consegue voltar a separá-las depois, e
        // a lentidão do servidor passa a parecer hesitação da pessoa.
        val s = Saida()
        val p = Progressao(s.emitir, { 0 }, { false }, { "" })
        p.espera(1400)
        p.espera(0)
        assertEquals(1, s.doTipo(Tipos.ESPERA).size)
        assertEquals(1400L, s.doTipo(Tipos.ESPERA).first().second)
    }

    @Test
    fun `no nivel essencial nada disto sai`() {
        val s = Saida()
        val p = Progressao(s.emitir, { 0 }, { true }, { "" })
        p.passo("/pagamento")
        p.espera(9000)
        p.ambiente("rede", "4g")
        assertEquals(0, s.eventos.size)
        // O terminal sai na mesma: é o que fecha a tentativa, e sem ele o abandono
        // e a conclusão misturam-se em todos os níveis.
        p.terminal(Terminal.SUCESSO)
        assertEquals(1, s.doTipo(Tipos.TERMINAL).size)
    }

    /* --------------------------------------------------------------- 4.5 */

    @Test
    fun `a amostragem do detalhado e outra coisa que a amostragem de medir`() {
        // A distinção é o ADR 0010: a `amostragem` decide **se** a pessoa é
        // medida, e a `amostragem_detalhado` decide **com que detalhe**. Com uma
        // só, subir o detalhe obrigava a subir para toda a gente, que é o custo
        // que a decisão evita.
        val c = Configuracao.deJson(
            org.json.JSONObject("""{"amostragem": 1, "nivel": "padrao", "amostragem_detalhado": 0.1}"""),
        )
        assertEquals(1.0, c.amostragem, 0.0)
        assertEquals(0.1, c.amostragemDetalhado, 0.0001)

        // Um valor absurdo não passa: uma fração acima de um punha toda a gente no
        // detalhado por engano, que é o acidente que isto evita.
        val absurdo = Configuracao.deJson(org.json.JSONObject("""{"amostragem_detalhado": 7}"""))
        assertEquals(1.0, absurdo.amostragemDetalhado, 0.0001)
        assertEquals(0.0, Configuracao().amostragemDetalhado, 0.0)
    }

    @Test
    fun `a amostragem do detalhado e deterministica, e por isso nao deixa buracos`() {
        val dentro = { id: String -> Ids.naAmostra("detalhado:$id", 0.2) }
        for (id in listOf("a1", "pessoa-2", "3f2504e0-4f89-41d3-9a0c-0305e82c3301")) {
            assertEquals("a mesma pessoa tem de dar sempre o mesmo", dentro(id), dentro(id))
        }
        var n = 0
        val total = 20000
        for (i in 0 until total) if (dentro("anonimo-$i")) n++
        val fracao = n.toDouble() / total
        assertTrue("esperava perto de 0,20 e deu $fracao", fracao > 0.18 && fracao < 0.22)
    }
}

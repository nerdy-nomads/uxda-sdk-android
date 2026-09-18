package io.uxda.sdk.inquerito

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import io.uxda.sdk.Evento
import io.uxda.sdk.Ids
import io.uxda.sdk.Seguranca
import io.uxda.sdk.fila.Transporte
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * O componente de inquérito, do gatilho ao envio. Cartões 14.1 e 14.2.
 *
 * O caminho, e cada passo existe para não se perguntar de mais (`RF-PER-05`):
 *
 * ```
 * evento → gatilho → sorteio → fadiga no dispositivo → servidor → atraso → cartão → resposta
 * ```
 *
 * 1. **O gatilho** lê os eventos que o SDK já captura. Uma regra muda na consola e
 *    aplica-se na sessão seguinte, sem publicar a aplicação.
 * 2. **O sorteio** usa a amostragem do inquérito, 0,1 por omissão. O pedido explícito
 *    da aplicação salta este passo, e só este.
 * 3. **A fadiga do dispositivo** poupa o pedido de rede quando já se sabe que não.
 * 4. **O servidor decide.** Sem resposta dele, não se mostra: a fadiga não se garante
 *    às cegas, e um servidor em baixo não pode virar "pergunta-se a toda a gente".
 * 5. **Um de cada vez, e um por sessão.** Nunca dois cartões ao mesmo tempo, e nunca
 *    duas perguntas na mesma sessão, venham de que gatilho vierem.
 *
 * # Fios
 *
 * Os eventos chegam no fio de fundo do SDK, a rede corre num fio próprio (um pedido
 * de elegibilidade lento não pode atrasar a escrita dos eventos em disco, que é o que
 * os salva de um processo abatido), e o cartão só se toca no fio principal. As
 * transições de fase fazem-se debaixo de um trinco, e **nenhuma espera de rede
 * acontece com ele fechado**.
 *
 * # Atividades
 *
 * Só se guardam **referências fracas** a atividades. Uma atividade rodada é destruída
 * e recriada, e um cartão agarrado à antiga prendia a árvore inteira dela em memória:
 * na rotação o cartão desaparece, e o pedido conta como mostrado e não respondido.
 */
internal class Inqueritos(
    private val prefs: SharedPreferences,
    private val transporte: Transporte,
    servidor: String,
    private val chaveDoProjeto: String,
    private val versaoApp: () -> String,
    private val anonimo: () -> String,
    private val utilizador: () -> String?,
    private val ecra: () -> String,
    private val passo: () -> String,
    private val sorteio: () -> Double = { Random.nextDouble() },
    private val agora: () -> Long = { System.currentTimeMillis() },
    emRede: ((() -> Unit) -> Unit)? = null,
    noPrincipal: ((Long, () -> Unit) -> Unit)? = null,
    private val fabricar: (Activity, Pedido, Tema, (Resposta) -> Unit, () -> Unit) -> View =
        { a, p, t, responder, fechar -> CartaoDoInquerito(a, p, t, responder, fechar) },
    private val esperaAntesDeRepetirMs: Long = 2_000L,
) : Application.ActivityLifecycleCallbacks {

    companion object {
        /** O agradecimento fica à vista o tempo de se ler, e sai sozinho. */
        const val AGRADECIMENTO_MS = 2_500L

        /**
         * Quanto tempo um pedido autorizado espera por uma atividade à vista. A pessoa
         * pode ter ido ao ecrã inicial no segundo do atraso; um minuto depois, a
         * pergunta já não é sobre o que acabou de fazer.
         */
        const val ESPERA_POR_ATIVIDADE_MS = 60_000L

        private const val K_SESSAO_PERGUNTADA = "sessao.perguntada"
    }

    private enum class Fase { LIVRE, A_PEDIR, A_ESPERAR, MOSTRADO }

    private val base = servidor.trimEnd('/')
    private val trinco = Any()
    private val gatilhos = Gatilhos(prefs)
    private val fadiga = FadigaLocal(prefs)

    @Volatile
    private var config = ConfigInqueritos.VAZIA

    private var fase = Fase.LIVRE
    private var pendente: Pedido? = null
    /**
     * O instante, no relógio do `Handler`, a partir do qual o pendente se pode mostrar.
     * É o que impede uma atividade que retoma de mostrar o cartão antes do `atraso_ms`.
     */
    private var mostrarAPartirDe = 0L
    /** Cada pedido autorizado tem a sua geração, para a expiração de um não apanhar o seguinte. */
    private var geracao = 0
    private var atividade: WeakReference<Activity>? = null
    private var cartao: WeakReference<View>? = null
    private var anfitria: WeakReference<Activity>? = null

    private var pedidos = 0
    private var autorizados = 0
    private var recusados = 0
    private var semResposta = 0
    private var mostrados = 0
    private var respondidos = 0
    private var enviadas = 0
    private var enviosFalhados = 0
    @Volatile
    private var ultimoMotivo = ""

    private var executor: ExecutorService? = null
    private val mao by lazy { Handler(Looper.getMainLooper()) }

    private val rede: (() -> Unit) -> Unit = emRede ?: { bloco ->
        val e = synchronized(trinco) {
            executor ?: Executors.newSingleThreadExecutor { r ->
                Thread(r, "uxda-inqueritos").apply { isDaemon = true }
            }.also { executor = it }
        }
        e.execute { Seguranca.executar("inqueritos.rede") { bloco() } }
    }

    private val principal: (Long, () -> Unit) -> Unit = noPrincipal ?: { atraso, bloco ->
        mao.postDelayed({ Seguranca.executar("inqueritos.principal") { bloco() } }, atraso)
    }

    /* --------------------------------------------------------------- entrada */

    fun configurar(c: ConfigInqueritos) {
        config = c
    }

    /** Há inquéritos para observar. Sem eles, os eventos nem chegam aqui. */
    val querEventos: Boolean get() = config.ativo

    /** Um evento capturado. Corre no fio de fundo do SDK. */
    fun observar(ev: Evento, instante: Long) = Seguranca.executar("inqueritos.observar") {
        val c = config
        if (!c.ativo) return@executar
        for (d in gatilhos.observar(ev, instante, c.lista)) {
            if (tentar(Pedido(d.inquerito, d.gatilho, d.tentativaInicio), sortear = true)) break
        }
    }

    /**
     * A aplicação pede um inquérito pela chave (`Uxda.inquerito`). Salta o sorteio, e
     * mais nada: a fadiga, o limite por sessão e a decisão do servidor valem na mesma,
     * senão uma chamada num ecrã visitado dez vezes por dia fazia dez perguntas.
     */
    fun pedir(chave: String) = Seguranca.executar("inqueritos.pedir") {
        val inq = config.porChave(chave)
        if (inq == null) {
            ultimoMotivo = "inexistente"
            return@executar
        }
        tentar(Pedido(inq, Gatilho.MANUAL, gatilhos.inicioEmCurso(chave, agora())), sortear = false)
    }

    /* ------------------------------------------------------------- o caminho */

    private fun recusar(motivo: String): Boolean {
        ultimoMotivo = motivo
        return false
    }

    private fun tentar(p: Pedido, sortear: Boolean): Boolean {
        synchronized(trinco) {
            if (fase != Fase.LIVRE) return recusar("outro_em_curso")
            val sessao = gatilhos.sessaoAtual
            if (sessao != null && prefs.getString(K_SESSAO_PERGUNTADA, null) == sessao) {
                return recusar("ja_perguntado_na_sessao")
            }
            // `<` e não `<=`: com amostragem zero não sai ninguém, nem com um sorteio
            // que devolva exatamente zero.
            if (sortear && !(sorteio() < p.inquerito.amostragem)) return recusar("fora_da_amostra")
            fadiga.impedimento(agora(), config.fadiga)?.let { return recusar(it) }
            fase = Fase.A_PEDIR
            pedidos++
        }
        rede { perguntarAoServidor(p) }
        return true
    }

    private fun cabecalhos() = mapOf("X-UXDA-Key" to chaveDoProjeto)

    private fun perguntarAoServidor(p: Pedido) {
        var autorizado: Pedido? = null
        try {
            val r = Seguranca.protegido("inqueritos.elegibilidade.rede", Transporte.Resposta(0, "")) {
                transporte.enviar(
                    "$base/v1/respostas/elegibilidade",
                    Corpos.elegibilidade(p.inquerito.chave, anonimo(), utilizador()),
                    cabecalhos(),
                )
            }
            val dados = if (r.estado in 200..299) {
                Seguranca.protegido("inqueritos.elegibilidade", null as JSONObject?) {
                    JSONObject(r.corpo).takeIf { it.opt("sucesso") == true }?.optJSONObject("dados")
                }
            } else {
                null
            }
            when {
                dados == null -> {
                    semResposta++
                    ultimoMotivo = "sem_resposta"
                }
                // Só o booleano `true` mostra. Um `"true"` em texto é um servidor a
                // responder mal, e na dúvida não se pergunta.
                dados.opt("mostrar") == true -> {
                    autorizados++
                    autorizado = p.copy(pedidoId = (dados.opt("pedido_id") as? String).orEmpty().take(128))
                }
                else -> {
                    recusados++
                    ultimoMotivo = (dados.opt("motivo") as? String)?.take(40) ?: "recusado"
                }
            }
        } finally {
            val ok = autorizado
            val minha = synchronized(trinco) {
                if (ok == null) {
                    fase = Fase.LIVRE
                } else {
                    // O servidor já contou este pedido, e o dispositivo conta-o também,
                    // mesmo que o cartão acabe por não aparecer: a fadiga é sobre ter
                    // sido pedido, e é o servidor que o diz.
                    fadiga.registarPedido(agora())
                    gatilhos.sessaoAtual?.let { prefs.edit().putString(K_SESSAO_PERGUNTADA, it).apply() }
                    fase = Fase.A_ESPERAR
                    pendente = ok
                    mostrarAPartirDe = SystemClock.uptimeMillis() + ok.inquerito.atrasoMs
                }
                ++geracao
            }
            if (ok != null) {
                principal(ok.inquerito.atrasoMs) { mostrarPendente() }
                principal(ok.inquerito.atrasoMs + ESPERA_POR_ATIVIDADE_MS) { expirarPendente(minha) }
            }
        }
    }

    private fun expirarPendente(de: Int) = synchronized(trinco) {
        if (fase == Fase.A_ESPERAR && geracao == de) {
            fase = Fase.LIVRE
            pendente = null
            ultimoMotivo = "sem_atividade"
        }
    }

    /** Mostra o pedido autorizado na atividade à vista. Só no fio principal. */
    private fun mostrarPendente() {
        val p: Pedido
        val a: Activity
        synchronized(trinco) {
            if (fase != Fase.A_ESPERAR) return
            // O relógio do `Handler`, e não o de parede: é contra ele que o atraso foi
            // agendado, e uma atividade que retoma a meio do atraso não o encurta.
            if (SystemClock.uptimeMillis() < mostrarAPartirDe) return
            p = pendente ?: run { fase = Fase.LIVRE; return }
            val atual = atividade?.get()
            // Sem atividade à vista, o pedido espera pela próxima que retome, até ao
            // limite: o `onActivityResumed` volta a chamar isto.
            if (atual == null || atual.isFinishing || atual.isDestroyed) return
            a = atual
        }
        var vista: View? = null
        val mostrou = Seguranca.protegido("inqueritos.desenhar", false) {
            val v = fabricar(a, p, config.tema, { r -> respondido(p, r) }, { fechado() })
            vista = v
            val contentor = a.findViewById<ViewGroup>(android.R.id.content)
                ?: (a.window?.decorView as? ViewGroup)
                ?: return@protegido false
            val parametros = (v as? CartaoDoInquerito)?.parametros()
                ?: android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM,
                )
            contentor.addView(v, parametros)
            true
        }
        synchronized(trinco) {
            if (mostrou) {
                fase = Fase.MOSTRADO
                cartao = vista?.let { WeakReference(it) }
                anfitria = WeakReference(a)
                mostrados++
                ultimoMotivo = "mostrado"
            } else {
                // **Um cartão a meio não fica pendurado no ecrã de ninguém**, e a fase
                // volta a livre: um desenho que rebentou não pode prender o componente
                // para o resto da vida do processo.
                fase = Fase.LIVRE
                ultimoMotivo = "erro_ao_desenhar"
            }
            pendente = null
        }
        if (!mostrou) {
            Seguranca.executar("inqueritos.limpar") { vista?.let { v -> (v.parent as? ViewGroup)?.removeView(v) } }
        }
    }

    /** A pessoa respondeu. Corre no fio principal, a partir do cartão. */
    private fun respondido(p: Pedido, r: Resposta) {
        val ecraAgora = ecra()
        val passoAgora = passo()
        synchronized(trinco) {
            respondidos++
            ultimoMotivo = "respondido"
        }
        fadiga.registarResposta(agora())
        rede { enviar(p, r, ecraAgora, passoAgora) }
        principal(AGRADECIMENTO_MS) { retirar() }
    }

    private fun fechado() {
        ultimoMotivo = "fechado"
        retirar()
    }

    /**
     * O envio, fora do fio principal. **Tenta outra vez uma vez**, com o mesmo
     * `resposta_id`: o servidor é idempotente, e uma resposta que chegou à primeira
     * e cuja confirmação se perdeu conta uma vez só.
     *
     * Uma recusa (`4xx`) não se repete: o corpo não vai mudar entre as duas tentativas.
     */
    private fun enviar(p: Pedido, r: Resposta, ecraAgora: String, passoAgora: String) {
        val envio = Corpos.Envio(
            respostaId = Ids.uuid(),
            anonimo = anonimo(),
            utilizador = utilizador(),
            ecra = ecraAgora,
            passo = passoAgora,
            versaoApp = versaoApp(),
        )
        for (tentativa in 1..2) {
            val corpo = Corpos.resposta(p, r, envio, agora())
            if (corpo == null) {
                ultimoMotivo = "resposta_invalida"
                synchronized(trinco) { enviosFalhados++ }
                return
            }
            val res = transporte.enviar("$base/v1/respostas", corpo, cabecalhos())
            if (res.estado in 200..299) {
                synchronized(trinco) { enviadas++ }
                return
            }
            if (res.estado in 400..499) {
                ultimoMotivo = "resposta_recusada"
                break
            }
            if (tentativa == 1) Thread.sleep(esperaAntesDeRepetirMs)
        }
        synchronized(trinco) { enviosFalhados++ }
        if (ultimoMotivo != "resposta_recusada") ultimoMotivo = "envio_falhou"
    }

    private fun retirar() = Seguranca.executar("inqueritos.retirar") {
        val v = synchronized(trinco) {
            val atual = cartao?.get()
            cartao = null
            anfitria = null
            if (fase == Fase.MOSTRADO) fase = Fase.LIVRE
            atual
        }
        v?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    /** Desliga tudo. Chamado pelo `Uxda.parar`. */
    fun parar() = Seguranca.executar("inqueritos.parar") {
        principal(0) { retirar() }
        synchronized(trinco) {
            executor?.shutdown()
            executor = null
        }
    }

    /** Um resumo para o diagnóstico. Contagens e o último motivo, e nada da pessoa. */
    fun resumo(): Map<String, Any?> = synchronized(trinco) {
        mapOf(
            "configurados" to config.lista.size,
            "fase" to fase.name.lowercase(),
            "pedidos" to pedidos,
            "autorizados" to autorizados,
            "recusados" to recusados,
            "semResposta" to semResposta,
            "mostrados" to mostrados,
            "respondidos" to respondidos,
            "enviadas" to enviadas,
            "enviosFalhados" to enviosFalhados,
            "ultimoMotivo" to ultimoMotivo,
        )
    }

    /* ---------------------------------------------------------- ensaios */

    internal fun cartaoParaEnsaio(): View? = synchronized(trinco) { cartao?.get() }

    internal fun faseParaEnsaio(): String = synchronized(trinco) { fase.name.lowercase() }

    /* ------------------------------------------------------ ciclo de vida */

    override fun onActivityResumed(a: Activity) = Seguranca.executar("inqueritos.retomada") {
        val esperava = synchronized(trinco) {
            atividade = WeakReference(a)
            fase == Fase.A_ESPERAR
        }
        if (esperava) principal(0) { mostrarPendente() }
    }

    override fun onActivityPaused(a: Activity) = Seguranca.executar("inqueritos.pausa") {
        synchronized(trinco) { if (atividade?.get() === a) atividade = null }
    }

    override fun onActivityDestroyed(a: Activity) = Seguranca.executar("inqueritos.destruida") {
        synchronized(trinco) {
            if (atividade?.get() === a) atividade = null
            if (anfitria?.get() === a) {
                // A rotação, ou a atividade a fechar com o cartão aberto. A árvore dela
                // vai com ela, cartão incluído; aqui só se larga a referência.
                cartao = null
                anfitria = null
                if (fase == Fase.MOSTRADO) {
                    fase = Fase.LIVRE
                    ultimoMotivo = "atividade_destruida"
                }
            }
        }
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
    override fun onActivityStarted(a: Activity) = Unit
    override fun onActivityStopped(a: Activity) = Unit
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
}

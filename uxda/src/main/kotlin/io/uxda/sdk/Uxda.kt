package io.uxda.sdk

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import io.uxda.sdk.captura.Captura
import io.uxda.sdk.fila.Armazem
import io.uxda.sdk.fila.Fila
import io.uxda.sdk.fila.Transporte
import io.uxda.sdk.identidade.Identidade
import org.json.JSONObject
import java.io.File

/**
 * O SDK Android da plataforma UX Data Analysis.
 *
 * A integração é **uma linha**, e não é código: é uma entrada no manifesto.
 *
 * ```xml
 * <meta-data android:name="io.uxda.chave" android:value="uxda_pro_..." />
 * ```
 *
 * Quem arranca o SDK é um `ContentProvider` do próprio SDK, que o Android cria
 * antes de a aplicação correr. Sem isso seria preciso mexer no `Application` de
 * quem nos instala, e a promessa do RF-CAP-01 deixava de valer no Android.
 *
 * **Nada disto corre no fio principal** (RNF-SDK-02), tirando a leitura do toque,
 * que tem de acontecer onde o toque acontece: essa está medida no cartão `3.4`.
 */
object Uxda {

    const val VERSAO = Fila.VERSAO

    private var app: Application? = null
    private var opcoes: Opcoes? = null
    private lateinit var identidade: Identidade
    private lateinit var fila: Fila
    private lateinit var armazem: Armazem
    private var captura: Captura? = null
    private var trabalho: HandlerThread? = null
    private var mao: Handler? = null

    private var configuracao = Configuracao.SEGURA
    private var origemConfig = "omissao"
    private var amostrado = true
    private var ecraAtual = "/"
    private var emitidos = 0
    private var recusados = 0
    private var msNoFioPrincipal = 0.0
    private var ligado = false

    /** Arranque manual, para quem prefere decidir o momento. */
    @JvmStatic
    fun iniciar(aplicacao: Application, op: Opcoes) = Seguranca.executar("uxda.iniciar") {
        if (ligado) return@executar
        ligado = true
        app = aplicacao
        opcoes = op

        val prefs = aplicacao.getSharedPreferences("uxda", Context.MODE_PRIVATE)
        identidade = Identidade(prefs)
        armazem = Armazem(File(aplicacao.filesDir, "uxda/fila.jsonl"))
        val transporte = Transporte()
        fila = Fila(
            armazem = armazem,
            transporte = transporte,
            url = op.servidor.trimEnd('/') + "/v1/eventos",
            cabecalhos = { mapOf("X-UXDA-Key" to op.chave) },
            estadoDaRede = { estadoDaRede(aplicacao) },
        )

        trabalho = HandlerThread("uxda").apply { start() }
        mao = Handler(trabalho!!.looper)

        emPlanoDeFundo {
            arrancarConfiguracao(transporte, op)
            if (!amostrado) return@emPlanoDeFundo
            // O que ficou de sessões anteriores sai primeiro: eventos guardados
            // enquanto não havia rede não esperam por um evento novo.
            armazem.aparar()
            fila.descarregar()
            agendarEnvio()
        }

        if (op.automatico) {
            val c = Captura(
                emitir = { tipo, elemento, duracao, extras -> emitirEvento(tipo, elemento, duracao, extras) },
                definirEcra = { ecraAtual = it },
            )
            captura = c
            aplicacao.registerActivityLifecycleCallbacks(c)
        }
    }

    /** Arranque a partir do manifesto. É o que o `UxdaProvider` chama. */
    @JvmStatic
    fun iniciarDoManifesto(contexto: Context) = Seguranca.executar("uxda.manifesto") {
        val app = contexto.applicationContext as? Application ?: return@executar
        val info = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
        val meta = info.metaData ?: return@executar
        val chave = meta.getString("io.uxda.chave") ?: return@executar
        val servidor = meta.getString("io.uxda.servidor") ?: "https://ingest.uxda.io"
        val automatico = meta.getBoolean("io.uxda.automatico", true)
        val versao = Seguranca.protegido("uxda.versaoApp", null as String?) {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }
        iniciar(app, Opcoes(chave = chave, servidor = servidor, versaoApp = versao, automatico = automatico))
    }

    /* ------------------------------------------------------- API pública */

    /** Marcação manual, para o que a captura automática não alcança (RF-CAP-08). */
    @JvmStatic
    fun track(nome: String, extras: Map<String, String> = emptyMap()) = Seguranca.executar("uxda.track") {
        emitirEvento(Tipos.PERSONALIZADO, null, null, extras + mapOf("message_key" to nome.take(256)))
    }

    /** Declara o ecrã, para navegação que não muda de atividade (Compose, abas). */
    @JvmStatic
    fun ecra(nome: String) = Seguranca.executar("uxda.ecra") {
        ecraAtual = nome.take(256)
        emitirEvento(Tipos.ECRA, null, null, emptyMap())
    }

    /**
     * Liga o anónimo ao pseudónimo depois da autenticação (RF-CAP-11).
     * **O que parecer um identificador direto é resumido aqui**, e o original não
     * sai do dispositivo (RNF-PRI-02).
     */
    @JvmStatic
    fun identificar(id: String) = Seguranca.executar("uxda.identificar") {
        val pseudo = Identidade.pseudonimizar(id)
        identidade.utilizador = pseudo
        val op = opcoes ?: return@executar
        emPlanoDeFundo {
            Transporte().enviar(
                op.servidor.trimEnd('/') + "/v1/identidade/ligar",
                JSONObject().put("anonymous_id", identidade.anonimo).put("user_id", pseudo).toString(),
                mapOf("X-UXDA-Key" to op.chave),
            )
        }
    }

    @JvmStatic
    fun esquecer() = Seguranca.executar("uxda.esquecer") { identidade.utilizador = null }

    /** Um erro de rede da aplicação anfitriã, para quem não usa OkHttp. */
    @JvmStatic
    fun erroDeRede(url: String, estado: Int) = Seguranca.executar("uxda.erroDeRede") {
        val destino = io.uxda.sdk.identidade.Elemento.normalizarDestino(url)
        emitirEvento(
            Tipos.ERRO_REDE,
            destino?.let { "v1|f=destino|d=" + it.take(120) },
            null,
            mapOf(
                "message_key" to if (estado > 0) "http_$estado" else "rede_indisponivel",
                "message_kind" to "erro",
            ),
        )
    }

    /** Força o envio do que está em fila. Corre fora do fio principal. */
    @JvmStatic
    fun descarregar() = Seguranca.executar("uxda.descarregar") { emPlanoDeFundo { fila.descarregar() } }

    @JvmStatic
    fun parar() = Seguranca.executar("uxda.parar") {
        captura?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        captura = null
        trabalho?.quitSafely()
        trabalho = null
        mao = null
        ligado = false
    }

    /** O que o SDK sabe agora. É por aqui que se vê o que ele está a fazer. */
    @JvmStatic
    fun diagnostico(): Map<String, Any?> = Seguranca.protegido("uxda.diagnostico", emptyMap()) {
        val e = if (::fila.isInitialized) fila.estado() else null
        mapOf(
            "versao" to VERSAO,
            "ligado" to ligado,
            "amostrado" to amostrado,
            "configuracao" to mapOf(
                "amostragem" to configuracao.amostragem,
                "nivel" to configuracao.nivel,
                "versao" to configuracao.versao,
            ),
            "origemDaConfiguracao" to origemConfig,
            "identidade" to mapOf(
                "anonimo" to (if (::identidade.isInitialized) identidade.anonimo else null),
                "dispositivo" to (if (::identidade.isInitialized) identidade.dispositivo else null),
                "utilizador" to (if (::identidade.isInitialized) identidade.utilizador else null),
            ),
            "fila" to mapOf(
                "pendentes" to e?.pendentes, "enviados" to e?.enviados, "falhas" to e?.falhas,
                "perdidos" to e?.perdidos, "bytes" to e?.bytes, "ultimoErro" to e?.ultimoErro,
            ),
            "ecra" to ecraAtual,
            "eventosEmitidos" to emitidos,
            "eventosRecusados" to recusados,
            "msNoFioPrincipal" to Math.round(msNoFioPrincipal * 100) / 100.0,
            "tempoAtivoMs" to (captura?.tempoAtivoMs ?: 0L),
            "errosInternos" to Seguranca.errosInternos().size,
        )
    }

    @JvmStatic
    fun diagnosticoEmTexto(): String = JSONObject(diagnostico()).toString(1)

    /* --------------------------------------------------------- por dentro */

    private fun emitirEvento(tipo: String, elemento: String?, duracao: Long?, extras: Map<String, String>) {
        val inicio = System.nanoTime()
        Seguranca.executar("uxda.emitir") {
            if (!ligado || !amostrado) return@executar
            if (!configuracao.capturaTipo(tipo)) return@executar
            val agora = System.currentTimeMillis()
            val ev = Evento(
                eventId = Ids.uuid(),
                anonymousId = identidade.anonimo,
                deviceId = identidade.dispositivo,
                sessionId = identidade.sessao(agora),
                eventType = tipo,
                screenKey = ecraAtual,
                occurredAt = Relogio.iso(agora),
                appVersion = opcoes?.versaoApp ?: "0.0.0",
                captureLevel = configuracao.nivel,
                userId = identidade.utilizador,
                elementKey = elemento,
                durationMs = duracao,
                messageKey = extras["message_key"],
                messageKind = extras["message_kind"],
            )
            emitidos++
            // A escrita em disco sai do fio principal: é o que o RNF-SDK-02 exige, e
            // é a diferença entre um SDK que ninguém nota e um que faz a lista tremer.
            emPlanoDeFundo { fila.juntar(ev) }
        }
        msNoFioPrincipal += (System.nanoTime() - inicio) / 1_000_000.0
    }

    private fun emPlanoDeFundo(bloco: () -> Unit) {
        val h = mao
        if (h == null) {
            Seguranca.executar("uxda.semFio") { bloco() }
            return
        }
        h.post { Seguranca.executar("uxda.fundo") { bloco() } }
    }

    /** Envio periódico. O agendamento é simples de propósito: um `Handler` chega. */
    private fun agendarEnvio() {
        val h = mao ?: return
        h.postDelayed(object : Runnable {
            override fun run() {
                Seguranca.executar("uxda.agendado") {
                    if (fila.podeEnviar()) fila.descarregar()
                }
                mao?.postDelayed(this, 5_000)
            }
        }, 5_000)
    }

    private fun arrancarConfiguracao(transporte: Transporte, op: Opcoes) {
        val prefs = app?.getSharedPreferences("uxda", Context.MODE_PRIVATE)
        val cache = prefs?.getString("uxda.config", null)?.let {
            Seguranca.protegido("uxda.cacheConfig", null as Configuracao?) { Configuracao.deJson(JSONObject(it)) }
        }
        val r = transporte.enviar(op.servidor.trimEnd('/') + "/v1/config", "",
            mapOf("X-UXDA-Key" to op.chave), "GET")
        configuracao = when {
            r.estado in 200..299 && r.corpo.isNotEmpty() -> Seguranca.protegido("uxda.config", cache ?: Configuracao.SEGURA) {
                val corpo = JSONObject(r.corpo)
                val dados = corpo.optJSONObject("dados") ?: corpo
                val c = Configuracao.deJson(dados)
                prefs?.edit()?.putString("uxda.config", dados.toString())?.apply()
                origemConfig = "servidor"
                c
            }
            cache != null -> { origemConfig = "cache"; cache }
            else -> { origemConfig = "omissao"; Configuracao.SEGURA }
        }
        amostrado = Ids.naAmostra(identidade.anonimo, configuracao.amostragem)
    }

    private fun estadoDaRede(contexto: Context): Fila.EstadoRede =
        Seguranca.protegido("uxda.rede", Fila.EstadoRede(true, false, false)) {
            val cm = contexto.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val pm = contexto.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val rede = cm?.activeNetwork
            val capacidades = rede?.let { cm.getNetworkCapabilities(it) }
            Fila.EstadoRede(
                ligado = capacidades != null,
                medida = cm?.isActiveNetworkMetered ?: false,
                poupanca = pm?.isPowerSaveMode ?: false,
            )
        }
}

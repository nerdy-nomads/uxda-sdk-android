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
import io.uxda.sdk.inquerito.Inqueritos
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
    private var inqueritos: Inqueritos? = null
    private var trabalho: HandlerThread? = null
    private var mao: Handler? = null

    private var configuracao = Configuracao.SEGURA
    private var origemConfig = "omissao"
    private var amostrado = true

    /**
     * Quem está na amostra do detalhado sobe de nível, e **está sempre**.
     *
     * A amostragem é determinística, por resumo do identificador anónimo, e não
     * aleatória por sessão: com aleatória, a mesma pessoa entra e sai da amostra e
     * as tentativas dela ficam com buracos, e uma tentativa com buracos deixa de
     * significar o que quer que seja (ADR 0010).
     */
    private var noDetalhe = false

    private fun nivelEfetivo(): String =
        if (noDetalhe && configuracao.nivel != "essencial") "detalhado" else configuracao.nivel
    private var ecraAtual = "/"
    private var emitidos = 0
    private var recusados = 0
    private var msNoFioPrincipal = 0.0

    /**
     * Pedidos da aplicação anfitriã em voo. É o que deixa dizer que um toque foi
     * dado **enquanto o sistema estava ocupado** (RF-GRA-05), que é uma coisa
     * diferente de um toque que não deu nada.
     */
    @Volatile
    private var emVoo = 0

    /**
     * O relógio do orçamento do fio principal é o **tempo de CPU do próprio fio**, e
     * não o relógio de parede. RNF-SDK-05.
     *
     * A diferença não é académica, e apareceu a medir: num emulador de um núcleo a
     * ser martelado por um guião, o fio principal é tirado do processador a meio do
     * nosso trabalho, e o relógio de parede conta a espera como se fosse trabalho
     * nosso. A primeira medição assim deu 139 ms por evento num sítio onde o fio
     * inteiro, incluindo a aplicação, gastou 38 ms de CPU: um número maior do que o
     * total é um número que está a medir outra coisa.
     *
     * O que o orçamento quer saber é quanto trabalho o SDK põe no fio que desenha o
     * ecrã, e isso é CPU.
     */
    private fun agoraNoFio(): Long = android.os.Debug.threadCpuTimeNanos()
    private var ligado = false

    /** Arranque manual, para quem prefere decidir o momento. */
    @JvmStatic
    fun iniciar(aplicacao: Application, op: Opcoes) = Seguranca.executar("uxda.iniciar") {
        if (ligado) return@executar
        ligado = true
        app = aplicacao
        opcoes = op
        // Antes de qualquer evento: a classe do dispositivo precisa dos recursos da
        // aplicação, e um evento emitido antes disto sairia com "desconhecido".
        Contexto.iniciar(aplicacao)

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

        // O componente de inquérito liga-se sempre, com ou sem captura automática: sem
        // inquéritos na configuração não faz nada, e com eles precisa de saber que
        // atividade está à vista desde a primeira. Numa barreira própria, para um
        // defeito dele nunca impedir a captura de arrancar.
        Seguranca.executar("uxda.inqueritos.ligar") {
            val inq = Inqueritos(
                prefs = aplicacao.getSharedPreferences("uxda.inqueritos", Context.MODE_PRIVATE),
                transporte = Transporte(6000),
                servidor = op.servidor,
                chaveDoProjeto = op.chave,
                versaoApp = { op.versaoApp ?: "0.0.0" },
                anonimo = { identidade.anonimo },
                utilizador = { identidade.utilizador },
                ecra = { ecraAtual },
                passo = { captura?.progressao?.passoAtual() ?: "" },
            )
            aplicacao.registerActivityLifecycleCallbacks(inq)
            inqueritos = inq
        }

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
                emitir = { tipo, elemento, duracao, extras, props -> emitirEvento(tipo, elemento, duracao, extras, props) },
                mensagemExposta = { chave -> configuracao.mensagensExpostas.contains(chave) },
                definirEcra = { ecraAtual = it },
                medir = { bloco -> noFioPrincipal(bloco) },
                nivel = { nivelEfetivo() },
                individual = { configuracao.rastreioIndividual },
                emVoo = { emVoo },
                aoIrParaTras = {
                    // A sessão fica gravada e o que está em fila sai agora. O sistema
                    // pode abater o processo no instante seguinte, e sem isto os
                    // eventos ficavam à espera do próximo arranque da aplicação.
                    identidade.guardarSessao()
                    emPlanoDeFundo { fila.descarregar() }
                },
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
    /**
     * Declara uma transição de passo dentro da tarefa (RF-GRA-20).
     *
     * Uma mudança de ecrã já conta como passo sozinha. Isto é para os fluxos que
     * acontecem no mesmo ecrã, que são a maioria dos assistentes por etapas.
     */
    @JvmStatic
    fun passo(nome: String) = Seguranca.executar("uxda.passo") {
        captura?.progressao?.passo(nome.take(64))
    }

    /**
     * Fecha a tentativa, sem ambiguidade (RF-GRA-23): `sucesso`, `erro`,
     * `abandonado` ou `expirado`.
     *
     * O `abandonado` sai sozinho quando a aplicação vai para trás com trabalho a
     * meio, e o `expirado` é normalmente do motor, que é quem conhece o limiar da
     * tarefa. Sem isto, o abandono e a conclusão misturam-se e todas as taxas
     * ficam erradas.
     */
    @JvmStatic
    fun terminal(estado: String) = Seguranca.executar("uxda.terminal") {
        captura?.progressao?.terminal(estado)
    }

    /**
     * Uma espera imposta pelo sistema, que não é hesitação de ninguém (RF-GRA-21).
     * O intercetor de OkHttp chama isto sozinho; quem não o usa chama-o à mão.
     */
    @JvmStatic
    fun espera(ms: Long) = Seguranca.executar("uxda.espera") {
        captura?.progressao?.espera(ms)
    }

    /** Conta um pedido da aplicação a entrar e a sair, para o RF-GRA-05 e o 21. */
    @JvmStatic
    fun pedidoComecou() = Seguranca.executar("uxda.pedido") { emVoo++ }

    @JvmStatic
    fun pedidoAcabou(duracaoMs: Long) = Seguranca.executar("uxda.pedido") {
        emVoo = (emVoo - 1).coerceAtLeast(0)
        // Abaixo de meio segundo ninguém espera por nada, e emitir um evento por
        // cada pedido rápido era trocar o volume que o cartão 4.5 poupou.
        if (duracaoMs >= 500) captura?.progressao?.espera(duracaoMs)
    }

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

    /**
     * Pede o inquérito com esta chave, agora (`RF-PER-01`).
     *
     * **Salta o sorteio, e só o sorteio.** A fadiga do dispositivo, o limite de uma
     * pergunta por sessão e a decisão do servidor valem como para os gatilhos: a
     * aplicação escolhe o momento, e não quantas vezes se pergunta a mesma pessoa. Corre
     * no fio de fundo, depois de a configuração remota ter chegado.
     */
    @JvmStatic
    fun inquerito(chave: String) = Seguranca.executar("uxda.inquerito") {
        val inq = inqueritos ?: return@executar
        emPlanoDeFundo { inq.pedir(chave.take(64)) }
    }

    @JvmStatic
    fun esquecer() = Seguranca.executar("uxda.esquecer") { identidade.utilizador = null }

    /**
     * Declara uma mensagem apresentada ao utilizador (RF-MSG-01, RF-MSG-02).
     *
     * A captura automática apanha o que aparece na árvore de vistas: `Snackbar`,
     * diálogos, vistas com o nome de recurso a dizer erro ou aviso, e o `setError`
     * de um campo. Isto é para o resto, e para quem prefere declarar a chave em vez
     * de deixar adivinhar pelo texto. **A chave ganha sempre ao texto**: é estável,
     * é independente do idioma e não arrasta dados nenhuns.
     */
    @JvmStatic
    @JvmOverloads
    fun mensagem(chave: String, tipo: String = "info", operacao: String? = null) = Seguranca.executar("uxda.mensagem") {
        captura?.mensagens?.declarar(chave.take(256), tipo, operacao)
    }

    /**
     * Declara um erro que **ninguém viu no ecrã** (RF-MSG-06).
     *
     * As falhas de rede e as respostas de erro do servidor já saem sozinhas pelo
     * intercetor. Isto é para o que a aplicação apanha e engole: uma resposta
     * ilegível, um passo que falhou em silêncio. São eles que explicam o abandono
     * que não tem explicação nenhuma no ecrã.
     */
    @JvmStatic
    @JvmOverloads
    fun erroTecnico(chave: String, operacao: String? = null, codigoHttp: Int = 0) = Seguranca.executar("uxda.erroTecnico") {
        val props = HashMap<String, Any>()
        props["classe_erro"] = "sistema"
        operacao?.let { props["operacao"] = it.take(32) }
        if (codigoHttp > 0) props["codigo_http"] = codigoHttp
        captura?.mensagens?.tecnico(chave.take(256), props)
    }

    /**
     * Um erro de rede da aplicação anfitriã, para quem não usa OkHttp.
     *
     * Os três casos vão distinguidos, e não somados (RF-MSG-06):
     *
     *   `rede_indisponivel`  o pedido não chegou a lado nenhum
     *   `rede_expirou`       chegou, e a resposta não veio a tempo
     *   `http_<n>`           chegou e respondeu, e a resposta é um erro
     */
    @JvmStatic
    @JvmOverloads
    fun erroDeRede(url: String, estado: Int, expirou: Boolean = false) = Seguranca.executar("uxda.erroDeRede") {
        val destino = io.uxda.sdk.identidade.Elemento.normalizarDestino(url) ?: ""
        val chave = if (expirou) "rede_expirou" else if (estado > 0) "http_$estado" else "rede_indisponivel"
        if (repetidoNaRede("$destino|$chave")) return@executar
        // A operação é o primeiro segmento do caminho: `/pagamentos/8412` dá
        // `pagamentos`. É o que permite a taxa de sucesso por operação do RF-MSG-17
        // sem ninguém instrumentar nada, e é de baixa cardinalidade de propósito.
        val operacao = destino.split("/").firstOrNull { it.isNotEmpty() && !it.startsWith("{") }?.take(32)
        val props = HashMap<String, Any>()
        // **Invisível ao utilizador**: é o que o distingue de uma mensagem de erro
        // no ecrã, e é a coluna por onde o catálogo os separa.
        props["visivel"] = false
        // 5xx é o sistema a falhar; 4xx é a operação a ser recusada, e é trabalho
        // de outra equipa. Uma queda de rede não é nem uma nem outra.
        props["classe_erro"] = if (estado >= 500 || estado == 0) "sistema" else "operacao"
        if (estado > 0) props["codigo_http"] = estado
        operacao?.let { props["operacao"] = it }
        emitirEvento(
            Tipos.ERRO_REDE,
            destino.ifEmpty { null }?.let { "v1|f=destino|d=" + it.take(120) },
            null,
            mapOf("message_key" to chave, "message_kind" to "erro"),
            props,
        )
    }

    /** A mesma rota a falhar dez vezes num segundo é uma falha, e não dez. */
    private val ultimasFalhas = HashMap<String, Long>()

    private fun repetidoNaRede(id: String): Boolean {
        val n = System.currentTimeMillis()
        val antes = ultimasFalhas[id]
        if (antes != null && n - antes < 3_000) return true
        ultimasFalhas[id] = n
        if (ultimasFalhas.size > 100) ultimasFalhas.entries.removeAll { n - it.value > 60_000 }
        return false
    }

    /** Força o envio do que está em fila. Corre fora do fio principal. */
    @JvmStatic
    fun descarregar() = Seguranca.executar("uxda.descarregar") { emPlanoDeFundo { fila.descarregar() } }

    @JvmStatic
    fun parar() = Seguranca.executar("uxda.parar") {
        captura?.let { app?.unregisterActivityLifecycleCallbacks(it) }
        captura = null
        inqueritos?.let {
            app?.unregisterActivityLifecycleCallbacks(it)
            it.parar()
        }
        inqueritos = null
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
            // O estado da rede entra no diagnóstico porque é ele que decide o
            // ritmo do envio: em rede medida ou com poupança de bateria ligada, o
            // SDK abranda em vez de desligar, e sem isto ninguém consegue ver que
            // foi isso que aconteceu.
            "rede" to (app?.let { a ->
                val r = estadoDaRede(a)
                mapOf("ligado" to r.ligado, "medida" to r.medida, "poupanca" to r.poupanca)
            } ?: emptyMap<String, Any>()),
            // O componente de inquérito: quantos pedidos, quantos o servidor autorizou,
            // quantos se mostraram e se responderam, e o motivo da última decisão. É a
            // única forma de saber porque é que um inquérito não apareceu.
            "inqueritos" to (inqueritos?.resumo() ?: emptyMap<String, Any>()),
            "eventosEmitidos" to emitidos,
            "eventosRecusados" to recusados,
            "msNoFioPrincipal" to Math.round(msNoFioPrincipal * 100) / 100.0,
            "tempoAtivoMs" to (captura?.tempoAtivoMs ?: 0L),
            "errosInternos" to Seguranca.errosInternos().size,
            // **Onde**, e não só quantos. Um contador diz que alguma coisa correu
            // mal e não deixa ninguém descobrir o quê, e este registo é a única
            // forma de o saber: a barreira engole tudo, de propósito.
            "ultimosErros" to Seguranca.errosInternos().takeLast(3)
                .map { "${it.onde}: ${it.erro.javaClass.simpleName}" },
        )
    }

    @JvmStatic
    fun diagnosticoEmTexto(): String = JSONObject(diagnostico()).toString(1)

    /* --------------------------------------------------------- por dentro */

    /**
     * Cronometra um bloco que corre no fio principal da interface, e é o único sítio
     * onde esse tempo se soma. RNF-SDK-05, cartão 3.4.
     *
     * O aninhamento é a razão de existir a bandeira: a leitura da vista chama a
     * emissão do evento por dentro, e somar as duas em separado contava a segunda
     * duas vezes. Quem estiver mais por fora é que conta.
     */
    private var aMedirFioPrincipal = false

    private fun noFioPrincipal(bloco: () -> Unit) {
        if (aMedirFioPrincipal) {
            bloco()
            return
        }
        val inicio = agoraNoFio()
        aMedirFioPrincipal = true
        try {
            bloco()
        } finally {
            aMedirFioPrincipal = false
            msNoFioPrincipal += (agoraNoFio() - inicio) / 1_000_000.0
        }
    }

    /**
     * O fio principal faz aqui **o mínimo**: lê o que só se pode ler onde o toque
     * acontece (o tipo, o elemento, a duração, o ecrã) e passa o resto para o fio
     * de fundo.
     *
     * O resto não é pouco: gerar um `UUID` usa a fonte segura de aleatoriedade do
     * sistema, e num telemóvel de gama baixa isso sozinho custa mais de um
     * milissegundo. A medição do cartão 3.4 dava 1,9 ms por evento com tudo aqui
     * dentro; com o identificador, o relógio e a sessão do outro lado, o fio
     * principal fica com o que não pode mesmo sair de cá.
     */
    private fun emitirEvento(
        tipo: String,
        elemento: String?,
        duracao: Long?,
        extras: Map<String, String>,
        propriedades: Map<String, Any>? = null,
    ) {
        val inicio = agoraNoFio()
        var contar = false
        Seguranca.executar("uxda.emitir") {
            if (!ligado) return@executar
            val paraFila = amostrado && configuracao.capturaTipo(tipo)
            // **Os gatilhos dos inquéritos veem os eventos que a amostra e o nível não
            // deixam sair.** A amostragem da captura decide o que se envia, e o
            // inquérito tem a sua própria amostragem: sem isto, uma pessoa fora da
            // amostra de medição nunca seria perguntada, e uma instituição no nível
            // essencial não podia ligar um gatilho ao `passo`. Nada disto sai do
            // dispositivo: o evento que não vai para a fila morre aqui.
            val observador = inqueritos?.takeIf { it.querEventos }
            if (!paraFila && observador == null) return@executar
            val agora = System.currentTimeMillis()
            val ecra = ecraAtual
            val versao = opcoes?.versaoApp ?: "0.0.0"
            val nivel = nivelEfetivo()
            contar = true
            if (paraFila) emitidos++
            emPlanoDeFundo {
                val ev = Evento(
                    eventId = Ids.uuid(),
                    anonymousId = identidade.anonimo,
                    deviceId = identidade.dispositivo,
                    sessionId = identidade.sessao(agora),
                    eventType = tipo,
                    screenKey = ecra,
                    occurredAt = Relogio.iso(agora),
                    appVersion = versao,
                    captureLevel = nivel,
                    userId = identidade.utilizador,
                    elementKey = elemento,
                    durationMs = duracao,
                    messageKey = extras["message_key"],
                    messageKind = extras["message_kind"],
                    messageTextMasked = extras["message_text_masked"],
                    properties = propriedades,
                )
                if (paraFila) fila.juntar(ev)
                // Depois da fila, e numa barreira própria: um gatilho que rebenta não
                // pode custar o evento.
                observador?.let { o -> Seguranca.executar("uxda.inqueritos.observar") { o.observar(ev, agora) } }
            }
        }
        // Uma emissão que venha de fora da captura (o `track` de quem integra, por
        // exemplo) conta aqui; a que vem de dentro já está dentro do bloco medido.
        if (contar && !aMedirFioPrincipal) msNoFioPrincipal += (agoraNoFio() - inicio) / 1_000_000.0
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
        inqueritos?.configurar(configuracao.inqueritos)
        amostrado = Ids.naAmostra(identidade.anonimo, configuracao.amostragem)
        // Sementes diferentes: quem está na amostra de ser medido não tem de ser a
        // mesma gente que está na amostra do detalhe.
        noDetalhe = Ids.naAmostra("detalhado:" + identidade.anonimo, configuracao.amostragemDetalhado)
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

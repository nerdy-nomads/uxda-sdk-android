package io.uxda.sdk.captura

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.TextView
import io.uxda.sdk.Seguranca
import io.uxda.sdk.Tipos
import io.uxda.sdk.identidade.Elemento
import io.uxda.sdk.identidade.ElementoCompose

/**
 * A captura automática dos dez tipos do RF-CAP-04, em Android.
 *
 * O que o integrador escreve para isto funcionar: **nada**. É o mesmo princípio do
 * SDK web, e é o produto: capturar primeiro, definir depois.
 *
 * Onde o Android obriga a fazer diferente da web, e porquê:
 *
 * | Evento | Na web | Aqui |
 * |---|---|---|
 * | `ecra` | mudança de URL | ciclo de vida da atividade, e dos fragmentos quando existem |
 * | `toque` | `click` no documento | `dispatchTouchEvent` da janela, com a vista debaixo do dedo |
 * | `tecla` | primeira tecla no campo | primeira alteração de texto depois do foco, porque os teclados virtuais **não** enviam teclas |
 * | `submissao` | evento `submit` | ação do teclado (Enviar, Seguinte, Concluído), encadeada sem tirar a da aplicação |
 * | `erro` | evento `invalid` | `setError` visível num campo, que é o mecanismo da plataforma |
 * | `recuo` | `popstate` | tecla de retroceder, e atividade a terminar com outra a retomar |
 * | `erro_rede` | `fetch` embrulhado | intercetor de OkHttp opcional, ou a API pública |
 *
 * Tudo o que aqui está passa pela barreira do RNF-SDK-01: **um erro nosso dentro
 * de um `dispatchTouchEvent` mata o processo da aplicação anfitriã**, e é por isso
 * que não há um único caminho sem `Seguranca`.
 */
class Captura(
    private val emitir: (
        tipo: String,
        elemento: String?,
        duracao: Long?,
        extras: Map<String, String>,
        propriedades: Map<String, Any>?,
    ) -> Unit,
    private val definirEcra: (String) -> Unit,
    private val agora: () -> Long = { System.currentTimeMillis() },
    /**
     * Envolve **todos** os pontos de entrada que correm no fio principal, e é assim
     * que o tempo deles se soma. Envolver em vez de cronometrar por dentro é o que
     * evita a conta errada: a leitura da vista e a emissão do evento acontecem uma
     * dentro da outra, e medi-las em separado somava a segunda duas vezes.
     *
     * **Inclui a leitura da vista**, que é a parte que não pode sair daqui: medir
     * só o que vem depois dava um número bonito e falso.
     */
    private val medir: (bloco: () -> Unit) -> Unit = { it() },
    /**
     * Chamado quando a aplicação deixa de estar à vista. É onde a sessão se grava e
     * onde a fila se despeja: é o instante em que uma tentativa costuma morrer, e o
     * sistema pode abater o processo logo a seguir sem avisar.
     */
    private val aoIrParaTras: () -> Unit = {},
    /** O nível de captura em vigor, que decide o que sai e o que não sai. */
    private val nivel: () -> String = { "padrao" },
    /** Pedidos da aplicação em voo, para saber se ela está ocupada (RF-GRA-05). */
    private val emVoo: () -> Int = { 0 },
) : Application.ActivityLifecycleCallbacks {

    /* ------------------------------------------------------ captura granular */

    private val detalhado = { nivel() == "detalhado" }
    private val essencial = { nivel() == "essencial" }

    private val emitirGranular: (String, String?, Long?, Map<String, Any>?) -> Unit =
        { tipo, elemento, duracao, props -> emitir(tipo, elemento, duracao, emptyMap(), props) }

    private val toques = Toques(emitirGranular, { agora() }, detalhado)
    private val campos = Campos(emitirGranular, { agora() }, detalhado, essencial)
    internal val progressao = Progressao(emitirGranular, { agora() }, essencial) { campos.campoDeAbandono() }

    private var atividadesVisiveis = 0
    private var emPrimeiroPlanoDesde = 0L
    /** Tempo ativo acumulado: o que o RF-CAP-04 chama primeiro plano, medido. */
    var tempoAtivoMs = 0L
        private set
    private var ultimaTeclaEm = HashMap<Int, Long>()
    private var errosVistos = HashSet<String>()


    /* --------------------------------------------------------- ciclo de vida */

    override fun onActivityCreated(a: Activity, b: Bundle?) = medir { Seguranca.executar("captura.criada") {
        embrulharJanela(a)
    } }

    override fun onActivityStarted(a: Activity) = medir { Seguranca.executar("captura.iniciada") {
        if (atividadesVisiveis == 0) emPrimeiroPlanoDesde = agora()
        atividadesVisiveis++
    } }

    override fun onActivityResumed(a: Activity) = medir { Seguranca.executar("captura.retomada") {
        definirEcra(nomeDoEcra(a))
        emitir(Tipos.ECRA, null, null, emptyMap(), null)
        // Um ecrã novo é um passo novo, e é onde a contagem até à primeira
        // interação recomeça: o RF-GRA-08 mede-a por ecrã, e não por sessão.
        toques.ecraNovo()
        progressao.passo(nomeDoEcra(a))
        campos.mostrar()
        progressao.mostrar()
        ligarFoco(a)
        registarFragmentos(a)
    } }

    override fun onActivityPaused(a: Activity) = Unit

    override fun onActivityStopped(a: Activity) = medir { Seguranca.executar("captura.parada") {
        atividadesVisiveis--
        if (atividadesVisiveis <= 0) {
            atividadesVisiveis = 0
            if (emPrimeiroPlanoDesde > 0) tempoAtivoMs += agora() - emPrimeiroPlanoDesde
            // A aplicação deixou de estar à vista. Na web isto é o
            // `visibilitychange`, e aqui é o sítio onde uma tentativa costuma
            // morrer: vale mais do que qualquer outro evento.
            emitir(Tipos.PLANO_FUNDO, null, tempoAtivoMs, emptyMap(), null)
            toques.fecharRajada()
            campos.esconder()
            aoIrParaTras()
            // O abandono marca-se **depois** do plano de fundo: a ordem no
            // armazenamento passa a ser a ordem em que as coisas aconteceram.
            progressao.esconder()
        }
    } }

    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit

    override fun onActivityDestroyed(a: Activity) = medir { Seguranca.executar("captura.destruida") {
        if (a.isFinishing && atividadesVisiveis > 0) emitir(Tipos.RECUO, null, null, emptyMap(), null)
    } }

    private fun nomeDoEcra(a: Activity): String {
        val nome = a.javaClass.simpleName.removeSuffix("Activity").ifEmpty { a.javaClass.simpleName }
        return "/" + nome.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
    }

    /**
     * Fragmentos, quando a aplicação os usa. O trabalho está no `Fragmentos`, que
     * só toca no `androidx.fragment` depois de confirmar que ele existe.
     */
    private fun registarFragmentos(a: Activity) {
        // O ciclo de vida dos fragmentos também corre no fio principal, e por isso
        // entra na mesma conta.
        Fragmentos.ligar(a, definirEcra) { tipo -> medir { emitir(tipo, null, null, emptyMap(), null) } }
    }



    /* ------------------------------------------------------------- toque */

    private fun embrulharJanela(a: Activity) {
        val janela = a.window ?: return
        val original = janela.callback ?: return
        if (original is CallbackDoUxda) return
        janela.callback = CallbackDoUxda(original, a)
    }

    /**
     * Embrulha o `Window.Callback` da atividade **chamando sempre o original**. É a
     * forma de ver os toques sem tirar nada à aplicação: se alguma coisa nossa
     * falhar, o evento segue para ela na mesma.
     */
    private inner class CallbackDoUxda(
        private val original: Window.Callback,
        private val atividade: Activity,
    ) : Window.Callback by original {

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_UP) {
                medir { Seguranca.executar("captura.toque") { anotarToque(atividade, ev.rawX, ev.rawY) } }
            }
            return original.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            medir { Seguranca.executar("captura.tecla.janela") {
                if (ev.action == KeyEvent.ACTION_UP && ev.keyCode == KeyEvent.KEYCODE_BACK) {
                    emitir(Tipos.RECUO, null, null, emptyMap(), null)
                }
                if (ev.action == KeyEvent.ACTION_UP &&
                    (ev.keyCode == KeyEvent.KEYCODE_ENTER || ev.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    val foco = atividade.currentFocus
                    if (foco is EditText) submeter(atividade, foco)
                }
            } }
            return original.dispatchKeyEvent(ev)
        }
    }

    private fun anotarToque(a: Activity, x: Float, y: Float) {
        val raiz = a.window?.decorView ?: return
        toques.primeiraInteracao()
        progressao.marcarAtividade()

        val vista = vistaNoPonto(raiz, x, y)
        // Um toque onde não havia nada acionável. É o sinal que o documento chama
        // dos mais subvalorizados que existem, e que nenhum funil revela.
        if (vista == null) {
            toques.semAlvo(x, y, raiz.width, raiz.height)
            return
        }
        val chave = chaveDe(vista, x, y)
        // Um botão desativado, e ninguém lhe disse porquê. Em Android a vista está
        // na árvore e vê-se; na web o browser nem despacha o evento.
        if (toques.estaDesativado(vista)) {
            toques.desativado(chave, x, y, raiz.width, raiz.height)
            return
        }
        if (chave == null) return
        if (emVoo() > 0) toques.emCarregamento(chave)
        toques.anotar(chave)
        emitir(Tipos.TOQUE, chave, null, emptyMap(), null)
        // A validação da plataforma aparece **depois** de uma ação, e não a cada
        // toque: um `setError` num campo é o `invalid` da web, e quem o dispara é
        // carregar num botão, não pousar o dedo numa lista.
        //
        // Varrer a árvore inteira a cada toque custava metade do orçamento do fio
        // principal na medição em gama baixa do cartão 3.4. Agora só se varre
        // depois de tocar em algo acionável.
        if (vista is android.widget.Button || vista.isClickable) procurarErros(raiz)
    }

    /**
     * A vista debaixo do dedo, do topo para baixo, e depois a subida até ao
     * acionável mais próximo: quem toca no ícone dentro do botão tocou no botão,
     * que é a unidade de funcionalidade do ADR 0007.
     */
    private fun vistaNoPonto(raiz: View, x: Float, y: Float): View? {
        val local = IntArray(2)
        fun dentro(v: View): Boolean {
            v.getLocationOnScreen(local)
            return x >= local[0] && x <= local[0] + v.width && y >= local[1] && y <= local[1] + v.height
        }
        fun procurar(v: View, nivel: Int): View? {
            if (nivel > 24 || v.visibility != View.VISIBLE || !dentro(v)) return null
            if (v is ViewGroup && !ElementoCompose.ehCompose(v)) {
                for (i in v.childCount - 1 downTo 0) {
                    procurar(v.getChildAt(i), nivel + 1)?.let { return it }
                }
            }
            return v
        }
        val achada = procurar(raiz, 0) ?: return null
        var subida: View? = achada
        var passos = 0
        while (subida != null && passos < 6) {
            if (Elemento.acionavel(subida) || ElementoCompose.ehCompose(subida)) return subida
            subida = subida.parent as? View
            passos++
        }
        return achada
    }

    /**
     * A chave de um elemento custa uma subida da árvore, uma contagem de irmãos e
     * um resumo do texto. Numa lista onde o dedo bate no mesmo sítio, isso repete-se
     * a cada toque, e foi metade do que sobrava do orçamento do fio principal.
     *
     * Guarda-se na própria vista, com validade curta: dois segundos chegam para
     * apanhar uma sequência de toques, e são pouco de mais para o texto do botão
     * mudar sem darmos por isso.
     */
    /** A mesma cache, para quem não tem coordenadas: a procura de erros. */
    private fun chaveCacheada(v: View): String? {
        val guardada = v.getTag(TAG_CHAVE) as? Pair<*, *>
        val quando = guardada?.second as? Long
        if (quando != null && agora() - quando < 2_000L) return guardada.first as? String
        val chave = Elemento.chaveDe(v).ifEmpty { null }
        v.setTag(TAG_CHAVE, Pair(chave, agora()))
        return chave
    }

    private fun chaveDe(v: View, x: Float, y: Float): String? {
        if (!ElementoCompose.ehCompose(v)) {
            val guardada = v.getTag(TAG_CHAVE) as? Pair<*, *>
            val quando = guardada?.second as? Long
            if (quando != null && agora() - quando < 2_000L) return guardada.first as? String
        }
        val chave = calcularChave(v, x, y)
        if (!ElementoCompose.ehCompose(v)) v.setTag(TAG_CHAVE, Pair(chave, agora()))
        return chave
    }

    private fun calcularChave(v: View, x: Float, y: Float): String? {
        if (ElementoCompose.ehCompose(v)) {
            val local = IntArray(2)
            v.getLocationOnScreen(local)
            ElementoCompose.sinaisNoPonto(v, x - local[0], y - local[1])?.let {
                return Elemento.serializar(it)
            }
        }
        return Elemento.chaveDe(v).ifEmpty { null }
    }

    /* ------------------------------------------------- foco, tecla, desfoco */

    private fun ligarFoco(a: Activity) {
        val raiz = a.window?.decorView ?: return
        val arvore = raiz.viewTreeObserver ?: return
        if (!arvore.isAlive) return
        arvore.addOnGlobalFocusChangeListener { antiga, nova ->
            medir { Seguranca.executar("captura.foco") {
                antiga?.let { sairDoCampo(it) }
                nova?.let { entrarNoCampo(it) }
            } }
        }
    }

    private fun entrarNoCampo(v: View) {
        if (v !is EditText) return
        // Quem guarda o estado do campo é o agregador: hesitação, contagens,
        // regressos e ordem saem todos num evento só, no desfoco (RF-GRA-29).
        campos.entrar(v)
        encadearAcaoDoTeclado(v)
    }

    private fun sairDoCampo(v: View) {
        if (v !is EditText) return
        campos.sair(v)
    }


    /**
     * A ação do teclado (Enviar, Seguinte, Concluído) é o `submit` da web. Encadeia
     * o ouvinte que a aplicação já tiver, e **chama-o sempre**: substituí-lo partia
     * o formulário de quem nos instalou.
     */
    private fun encadearAcaoDoTeclado(v: EditText) {
        Seguranca.executar("captura.acaoTeclado") {
            val atual = ouvinteAtualDeAcao(v)
            // **Se não se conseguir ler o que lá está, não se põe nada.** Encadear
            // às cegas é o mesmo que substituir: o ouvinte da aplicação
            // desaparecia, e o formulário de quem nos instalou deixava de submeter.
            // Perde-se o sinal de submissão naquele campo, e é o lado certo para
            // falhar. O ADR 0019 diz isto por palavras, e a leitura por reflexão
            // falhou mesmo, em Android 16, com `NoSuchFieldException`.
            if (!atual.lido) return@executar
            val anterior = atual.ouvinte
            v.setOnEditorActionListener { alvo, acao, evento ->
                medir { Seguranca.executar("captura.submissao") {
                    if (acao == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                        acao == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                        acao == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                        acao == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    ) {
                        (alvo.context as? Activity)?.let { submeter(it, alvo as View) }
                    }
                } }
                anterior?.onEditorAction(alvo, acao, evento) ?: false
            }
        }
    }

    /**
     * O que lá está agora, e **se foi possível sabê-lo**.
     *
     * A distinção é o que interessa: um ouvinte nulo quer dizer que a aplicação não
     * pôs nenhum, e aí podemos pôr o nosso; não conseguir ler quer dizer que não
     * sabemos, e aí não se toca em nada.
     */
    internal class OuvinteAtual(val lido: Boolean, val ouvinte: TextView.OnEditorActionListener?)

    /**
     * O ouvinte da ação do teclado vive num campo privado, e o caminho até lá mudou
     * entre versões do Android: já esteve no próprio `TextView`, e está no `Editor`
     * nas versões recentes. Por isso procura-se pelos dois lados, e o campo procura-se
     * a subir a hierarquia de classes em vez de se exigir que esteja na folha.
     */
    internal fun ouvinteAtualDeAcao(v: EditText): OuvinteAtual {
        var lido = false
        var achado: TextView.OnEditorActionListener? = null
        Seguranca.executar("captura.ouvinteAcao") {
            val tipo = valorDoCampo(v, "mInputContentType")
                ?: valorDoCampo(v, "mEditor")?.let { valorDoCampo(it, "mInputContentType") }
            if (tipo == null) {
                // Sem tipo de conteúdo não há ouvinte nenhum guardado: a aplicação
                // não pôs nada, e o campo é nosso sem tirar nada a ninguém.
                lido = true
                return@executar
            }
            val campo = campoNaHierarquia(tipo.javaClass, "onEditorActionListener") ?: return@executar
            achado = campo.get(tipo) as? TextView.OnEditorActionListener
            lido = true
        }
        return OuvinteAtual(lido, achado)
    }

    private fun valorDoCampo(alvo: Any, nome: String): Any? {
        val campo = campoNaHierarquia(alvo.javaClass, nome) ?: return null
        return campo.get(alvo)
    }

    private fun campoNaHierarquia(classe: Class<*>, nome: String): java.lang.reflect.Field? {
        var atual: Class<*>? = classe
        while (atual != null) {
            try {
                return atual.getDeclaredField(nome).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                atual = atual.superclass
            }
        }
        return null
    }

    private fun submeter(a: Activity, origem: View) {
        // O retrato de cada campo sai primeiro, e a contagem vai no próprio evento
        // de submissão: quem olha para ela vê logo se foi feita com metade do
        // formulário vazio (RF-GRA-19).
        val (preenchidos, vazios, comErro) = campos.aoSubmeter(a.window?.decorView)
        emitir(
            Tipos.SUBMISSAO, Elemento.chaveDe(origem).ifEmpty { null }, null, emptyMap(),
            mapOf(
                "campos_preenchidos" to preenchidos,
                "campos_vazios" to vazios,
                "campos_com_erro" to comErro,
            ),
        )
        a.window?.decorView?.let { procurarErros(it) }
    }

    /* ---------------------------------------------------- erro de validação */

    /**
     * O `setError` de um campo é o mecanismo de validação da plataforma, e o
     * `TextInputLayout` do Material usa o mesmo nome. Lê-se por reflexão para
     * apanhar os dois sem depender de nenhum.
     *
     * **A mensagem de erro não é lida.** Ela costuma trazer o valor que a pessoa
     * escreveu lá dentro, e o que interessa é qual o campo que falhou.
     */
    private fun procurarErros(raiz: View) {
        Seguranca.executar("captura.erros") {
            val encontrados = HashSet<String>()
            percorrer(raiz, 0) { v ->
                if (temErro(v)) {
                    // Pela cache, e não outra vez do zero: um campo com erro fica
                    // com erro, e cada toque seguinte voltava a subir a árvore e a
                    // contar irmãos para chegar à mesma chave.
                    val chave = chaveCacheada(v)
                    val id = chave ?: v.hashCode().toString()
                    encontrados.add(id)
                    if (id !in errosVistos) {
                        // Quem conta as tentativas até resolver é o agregador, que
                        // sabe quantas vezes aquele campo já falhou (RF-GRA-17).
                        emitir(
                            Tipos.ERRO, chave, null,
                            mapOf("message_key" to "validacao_nativa", "message_kind" to "erro"),
                            campos.aoErrar(v, "validacao_nativa"),
                        )
                    }
                }
            }
            errosVistos = encontrados
        }
    }

    // O método é o mesmo para todas as vistas da mesma classe: procura-se uma vez.
    //
    // E é preciso guardar **também as classes que não o têm**, que são quase todas.
    // A primeira versão usava `getOrPut`, e o `getOrPut` volta a calcular sempre que
    // o valor guardado é nulo: para um `LinearLayout` ou um `ScrollView` a cache
    // nunca acertava, e cada toque voltava a pedir a lista completa de métodos de
    // cada vista da árvore. São umas centenas de métodos por vista, umas dezenas de
    // vistas por ecrã, a cada toque.
    //
    // Isto era **99% do custo do SDK no fio principal**: 33,8 ms dos 34 ms por
    // evento, medidos num emulador de um núcleo. A leitura da vista, que era o
    // suspeito óbvio, custava 0,13 ms.
    internal val metodoDeErro = HashMap<Class<*>, java.lang.reflect.Method?>()

    internal fun temErro(v: View): Boolean = Seguranca.protegido("captura.temErro", false) {
        // O caminho comum não precisa de reflexão nenhuma: `getError` é API do
        // `TextView`, e é dele que descendem os campos e os botões onde a validação
        // da plataforma aparece. A reflexão fica para a vista feita em casa que
        // tenha o método sem descender dali.
        if (v is android.widget.TextView) return@protegido !v.error.isNullOrEmpty()
        val classe = v.javaClass
        val m = if (metodoDeErro.containsKey(classe)) {
            metodoDeErro[classe]
        } else {
            classe.methods.firstOrNull { it.name == "getError" && it.parameterCount == 0 }
                .also { metodoDeErro[classe] = it }
        } ?: return@protegido false
        (m.invoke(v) as? CharSequence)?.isNotEmpty() == true
    }

    private fun percorrer(v: View, nivel: Int, bloco: (View) -> Unit) {
        // Uma vista escondida não mostra erro nenhum a ninguém, e o ramo debaixo
        // dela também não. Numa aplicação com abas ou com um menu lateral fechado,
        // isso é a maior parte da árvore.
        if (nivel > 24 || v.visibility != View.VISIBLE) return
        bloco(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i), nivel + 1, bloco)
    }

    companion object {
        private val TAG_OUVINTE = "uxda.ouvinte".hashCode()
        private val TAG_CHAVE = "uxda.chave".hashCode()
    }
}

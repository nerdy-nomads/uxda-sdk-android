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
    private val emitir: (tipo: String, elemento: String?, duracao: Long?, extras: Map<String, String>) -> Unit,
    private val definirEcra: (String) -> Unit,
    private val agora: () -> Long = { System.currentTimeMillis() },
) : Application.ActivityLifecycleCallbacks {

    private var atividadesVisiveis = 0
    private var emPrimeiroPlanoDesde = 0L
    /** Tempo ativo acumulado: o que o RF-CAP-04 chama primeiro plano, medido. */
    var tempoAtivoMs = 0L
        private set
    private var ultimaTeclaEm = HashMap<Int, Long>()
    private val camposFocados = HashMap<Int, EstadoCampo>()
    private var errosVistos = HashSet<String>()

    private class EstadoCampo(val focadoEm: Long, val chave: String?, var jaEscreveu: Boolean)

    /* --------------------------------------------------------- ciclo de vida */

    override fun onActivityCreated(a: Activity, b: Bundle?) = Seguranca.executar("captura.criada") {
        embrulharJanela(a)
    }

    override fun onActivityStarted(a: Activity) = Seguranca.executar("captura.iniciada") {
        if (atividadesVisiveis == 0) emPrimeiroPlanoDesde = agora()
        atividadesVisiveis++
    }

    override fun onActivityResumed(a: Activity) = Seguranca.executar("captura.retomada") {
        definirEcra(nomeDoEcra(a))
        emitir(Tipos.ECRA, null, null, emptyMap())
        ligarFoco(a)
        registarFragmentos(a)
    }

    override fun onActivityPaused(a: Activity) = Unit

    override fun onActivityStopped(a: Activity) = Seguranca.executar("captura.parada") {
        atividadesVisiveis--
        if (atividadesVisiveis <= 0) {
            atividadesVisiveis = 0
            if (emPrimeiroPlanoDesde > 0) tempoAtivoMs += agora() - emPrimeiroPlanoDesde
            // A aplicação deixou de estar à vista. Na web isto é o
            // `visibilitychange`, e aqui é o sítio onde uma tentativa costuma
            // morrer: vale mais do que qualquer outro evento.
            emitir(Tipos.PLANO_FUNDO, null, tempoAtivoMs, emptyMap())
        }
    }

    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit

    override fun onActivityDestroyed(a: Activity) = Seguranca.executar("captura.destruida") {
        if (a.isFinishing && atividadesVisiveis > 0) emitir(Tipos.RECUO, null, null, emptyMap())
    }

    private fun nomeDoEcra(a: Activity): String {
        val nome = a.javaClass.simpleName.removeSuffix("Activity").ifEmpty { a.javaClass.simpleName }
        return "/" + nome.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
    }

    /**
     * Fragmentos, quando a aplicação os usa. Feito por reflexão de propósito: o
     * `androidx.fragment` é uma dependência que muita aplicação tem e outras não,
     * e o SDK não pode obrigar ninguém a levá-la.
     */
    private fun registarFragmentos(a: Activity) {
        val obter = a.javaClass.methods.firstOrNull {
            it.name == "getSupportFragmentManager" && it.parameterCount == 0
        } ?: return
        val gestor = obter.invoke(a) ?: return
        val classeCallbacks = Class.forName("androidx.fragment.app.FragmentManager\$FragmentLifecycleCallbacks")
        if (fragmentosRegistados.contains(gestor)) return
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            classeCallbacks.classLoader, arrayOf(classeCallbacks)
        ) { _, metodo, args ->
            Seguranca.executar("captura.fragmento") {
                if (metodo.name == "onFragmentResumed" && args != null && args.size >= 2) {
                    val f = args[1]
                    val nome = f.javaClass.simpleName.removeSuffix("Fragment")
                    definirEcra("/" + nome.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase())
                    emitir(Tipos.ECRA, null, null, emptyMap())
                }
            }
            null
        }
        // O proxy dinâmico só serve para uma interface; para uma classe abstrata é
        // preciso outra via, e é por isso que isto degrada em silêncio quando não dá.
        Seguranca.executar("captura.registarFragmentos") {
            val registar = gestor.javaClass.methods.firstOrNull { it.name == "registerFragmentLifecycleCallbacks" }
            registar?.invoke(gestor, proxy, true)
            fragmentosRegistados.add(gestor)
        }
    }

    private val fragmentosRegistados = HashSet<Any>()

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
            Seguranca.executar("captura.toque") {
                if (ev.action == MotionEvent.ACTION_UP) anotarToque(atividade, ev.rawX, ev.rawY)
            }
            return original.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
            Seguranca.executar("captura.tecla.janela") {
                if (ev.action == KeyEvent.ACTION_UP && ev.keyCode == KeyEvent.KEYCODE_BACK) {
                    emitir(Tipos.RECUO, null, null, emptyMap())
                }
                if (ev.action == KeyEvent.ACTION_UP &&
                    (ev.keyCode == KeyEvent.KEYCODE_ENTER || ev.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    val foco = atividade.currentFocus
                    if (foco is EditText) submeter(atividade, foco)
                }
            }
            return original.dispatchKeyEvent(ev)
        }
    }

    private fun anotarToque(a: Activity, x: Float, y: Float) {
        val raiz = a.window?.decorView ?: return
        val vista = vistaNoPonto(raiz, x, y) ?: return
        val chave = chaveDe(vista, x, y) ?: return
        emitir(Tipos.TOQUE, chave, null, emptyMap())
        // A validação da plataforma aparece **depois** do toque: um `setError` num
        // campo é o `invalid` da web, e é aqui que se vê.
        procurarErros(raiz)
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

    private fun chaveDe(v: View, x: Float, y: Float): String? {
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
            Seguranca.executar("captura.foco") {
                antiga?.let { sairDoCampo(it) }
                nova?.let { entrarNoCampo(it) }
            }
        }
    }

    private fun entrarNoCampo(v: View) {
        if (v !is EditText) return
        val chave = Elemento.chaveDe(v).ifEmpty { null }
        camposFocados[v.hashCode()] = EstadoCampo(agora(), chave, false)
        emitir(Tipos.FOCO, chave, null, emptyMap())
        ouvirPrimeiraTecla(v)
    }

    private fun sairDoCampo(v: View) {
        if (v !is EditText) return
        val estado = camposFocados.remove(v.hashCode())
        val duracao = estado?.let { agora() - it.focadoEm }
        emitir(Tipos.DESFOCO, estado?.chave ?: Elemento.chaveDe(v).ifEmpty { null }, duracao, emptyMap())
    }

    /**
     * **A primeira tecla, e nunca o que foi escrito.** Um teclado virtual não envia
     * teclas: envia texto já composto. Por isso o sinal é a primeira alteração do
     * campo depois do foco, e o que se guarda é o tempo até lá (a hesitação), sem
     * ler um único caractere.
     */
    private fun ouvirPrimeiraTecla(v: EditText) {
        val marca = v.getTag(TAG_OUVINTE)
        if (marca == true) return
        v.setTag(TAG_OUVINTE, true)
        v.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                Seguranca.executar("captura.tecla") {
                    val estado = camposFocados[v.hashCode()] ?: return@executar
                    if (estado.jaEscreveu) return@executar
                    estado.jaEscreveu = true
                    emitir(Tipos.TECLA, estado.chave, agora() - estado.focadoEm, emptyMap())
                }
            }
        })
        encadearAcaoDoTeclado(v)
    }

    /**
     * A ação do teclado (Enviar, Seguinte, Concluído) é o `submit` da web. Encadeia
     * o ouvinte que a aplicação já tiver, e **chama-o sempre**: substituí-lo partia
     * o formulário de quem nos instalou.
     */
    private fun encadearAcaoDoTeclado(v: EditText) {
        Seguranca.executar("captura.acaoTeclado") {
            val anterior = ouvinteAtualDeAcao(v)
            v.setOnEditorActionListener { alvo, acao, evento ->
                Seguranca.executar("captura.submissao") {
                    if (acao == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                        acao == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                        acao == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                        acao == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                    ) {
                        (alvo.context as? Activity)?.let { submeter(it, alvo as View) }
                    }
                }
                anterior?.onEditorAction(alvo, acao, evento) ?: false
            }
        }
    }

    private fun ouvinteAtualDeAcao(v: EditText): TextView.OnEditorActionListener? =
        Seguranca.protegido("captura.ouvinteAcao", null) {
            val campoEditor = TextView::class.java.getDeclaredField("mEditor").apply { isAccessible = true }
            val editor = campoEditor.get(v) ?: return@protegido null
            val campoTipo = editor.javaClass.getDeclaredField("mInputContentType").apply { isAccessible = true }
            val tipo = campoTipo.get(editor) ?: return@protegido null
            val campoOuvinte = tipo.javaClass.getDeclaredField("onEditorActionListener").apply { isAccessible = true }
            campoOuvinte.get(tipo) as? TextView.OnEditorActionListener
        }

    private fun submeter(a: Activity, origem: View) {
        emitir(Tipos.SUBMISSAO, Elemento.chaveDe(origem).ifEmpty { null }, null, emptyMap())
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
                    val chave = Elemento.chaveDe(v).ifEmpty { null }
                    val id = chave ?: v.hashCode().toString()
                    encontrados.add(id)
                    if (id !in errosVistos) {
                        emitir(Tipos.ERRO, chave, null, mapOf("message_key" to "validacao_nativa", "message_kind" to "erro"))
                    }
                }
            }
            errosVistos = encontrados
        }
    }

    private fun temErro(v: View): Boolean = Seguranca.protegido("captura.temErro", false) {
        val m = v.javaClass.methods.firstOrNull { it.name == "getError" && it.parameterCount == 0 }
            ?: return@protegido false
        (m.invoke(v) as? CharSequence)?.isNotEmpty() == true
    }

    private fun percorrer(v: View, nivel: Int, bloco: (View) -> Unit) {
        if (nivel > 24) return
        bloco(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i), nivel + 1, bloco)
    }

    companion object {
        private val TAG_OUVINTE = "uxda.ouvinte".hashCode()
    }
}

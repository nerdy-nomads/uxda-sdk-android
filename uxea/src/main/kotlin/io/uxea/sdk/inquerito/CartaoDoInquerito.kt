package io.uxea.sdk.inquerito

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import io.uxea.sdk.Seguranca
import io.uxea.sdk.captura.VistaDoSdk
import kotlin.math.ceil
import kotlin.math.floor

/**
 * O componente de avaliação. Cartão 14.1, `RF-PER-01` a `RF-PER-03`.
 *
 * # Um cartão no fundo do ecrã, e não um diálogo
 *
 * **Não bloqueia nada**, e a diferença não é de gosto: um diálogo rouba o foco, escurece
 * o ecrã e obriga a responder ou a fechar antes de continuar, e uma pergunta sobre o
 * esforço de uma tarefa que interrompe a tarefa seguinte mede o incómodo que ela
 * própria causou. O cartão é uma vista acrescentada ao contentor da atividade, por
 * cima do fundo do ecrã: o resto continua a responder ao dedo, sem véu, e quem não
 * quer responder simplesmente continua.
 *
 * Tem a sua própria superfície de toque (é `clickable`), para um toque na margem do
 * cartão não cair no botão da aplicação que está por baixo dele.
 *
 * # Porque é que é desenhado em código, e não num esquema XML
 *
 * Porque um recurso de uma biblioteca entra no espaço de nomes da aplicação que a
 * instala, e o tema dela aplica-se-lhe: um `Button` inflado dentro de uma aplicação
 * Material recebe a cor de fundo do tema dela e ignora a da instituição. O botão de
 * enviar é por isso um `TextView` com o papel de botão declarado a quem lê o ecrã, e
 * as opções da escala são `RadioButton` sem o círculo, com o fundo desenhado aqui.
 *
 * # Acessível sem ninguém pedir
 *
 * Todas as áreas de toque têm pelo menos 48 dp, que é o mínimo das diretrizes da
 * plataforma; uma escala que não cabe numa linha com esse mínimo parte-se em duas,
 * em vez de encolher. Cada nota diz o que é a quem lê o ecrã ("6 de 7, muito fácil"),
 * o cartão anuncia-se como painel, e o agradecimento é uma região viva.
 *
 * **É uma `VistaDoSdk`**: a captura não lê nada do que acontece aqui dentro, e é essa
 * marca que o impede de aparecer nas métricas da aplicação que o mostra.
 */
@SuppressLint("ViewConstructor")
internal class CartaoDoInquerito(
    contexto: Context,
    private val pedido: Pedido,
    private val tema: Tema,
    private val aoResponder: (Resposta) -> Unit,
    private val aoFechar: () -> Unit,
    private val agora: () -> Long = { System.currentTimeMillis() },
) : LinearLayout(contexto), VistaDoSdk {

    private val inq = pedido.inquerito
    private val ingles = tema.ingles
    private val densidade = resources.displayMetrics.density

    private var nota: Int? = null
    private val escolhidas = LinkedHashSet<String>()
    private var campoTexto: EditText? = null
    private lateinit var enviar: TextView
    private val notas = ArrayList<RadioButton>()
    private var respondido = false

    companion object {
        /** A largura máxima num tablet: um cartão de ponta a ponta num ecrã de 1200 dp lê-se mal. */
        const val LARGURA_MAXIMA_DP = 560
        const val MARGEM_DP = 12
        private const val PADDING_DP = 16
        const val TOQUE_MINIMO_DP = 48

        /**
         * A família do sistema que corresponde à pilha de fontes de CSS do tema.
         *
         * A consola escreve a fonte como a web a escreve (`system-ui, sans-serif`), e o
         * Android não carrega fontes por nome de CSS. Percorre-se a pilha e fica a
         * primeira que o sistema tem; o que não se reconhece cai na sem serifa, que é o
         * que `system-ui` quer dizer num telemóvel.
         */
        fun familiaDe(fonte: String): String {
            val sistema = setOf(
                "sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-thin",
                "sans-serif-black", "sans-serif-condensed", "sans-serif-smallcaps",
                "serif", "monospace", "serif-monospace", "casual", "cursive",
            )
            for (bruta in fonte.split(',')) {
                val f = bruta.trim().trim('"', '\'').lowercase()
                when {
                    f in sistema -> return f
                    f == "system-ui" || f == "-apple-system" || f == "roboto" || f == "helvetica" ||
                        f == "arial" || f == "ui-sans-serif" -> return "sans-serif"
                    f == "ui-serif" || f == "georgia" || f == "times new roman" || f == "times" -> return "serif"
                    f == "ui-monospace" || f == "menlo" || f == "consolas" || f == "courier new" -> return "monospace"
                }
            }
            return "sans-serif"
        }

        /** Texto escuro sobre cor clara, texto branco sobre cor escura. */
        fun contraste(cor: Int): Int {
            val luz = 0.299 * Color.red(cor) + 0.587 * Color.green(cor) + 0.114 * Color.blue(cor)
            return if (luz > 160) 0xFF1B1F24.toInt() else Color.WHITE
        }

        fun comAlfa(cor: Int, alfa: Int): Int = (cor and 0x00FFFFFF) or (alfa.coerceIn(0, 255) shl 24)
    }

    private fun dp(v: Int): Int = (v * densidade + 0.5f).toInt()
    private fun letra(estilo: Int = Typeface.NORMAL): Typeface = Typeface.create(familiaDe(tema.fonte), estilo)
    private fun t(pt: String, en: String) = if (ingles) en else pt

    init {
        orientation = VERTICAL
        setPadding(dp(PADDING_DP), dp(8), dp(PADDING_DP), dp(PADDING_DP))
        background = GradientDrawable().apply {
            setColor(tema.corFundo)
            cornerRadius = tema.cantosPx * densidade
            setStroke(dp(1), comAlfa(tema.corTexto, 0x26))
        }
        elevation = dp(8).toFloat()
        // A superfície do cartão é dele: um toque na margem não pode atravessar para o
        // botão da aplicação que está por baixo.
        isClickable = true
        isFocusable = false
        if (Build.VERSION.SDK_INT >= 28) accessibilityPaneTitle = t("Inquérito", "Survey")

        construirCabecalho()
        when (inq.formato) {
            Formatos.ESFORCO -> construirEscala(t("Muito difícil", "Very difficult"), t("Muito fácil", "Very easy"))
            Formatos.SATISFACAO -> construirEscala(t("Nada satisfeito", "Not satisfied at all"), t("Muito satisfeito", "Very satisfied"))
            Formatos.RECOMENDACAO -> construirEscala(t("Nada provável", "Not at all likely"), t("Muito provável", "Extremely likely"))
            Formatos.ESCOLHA -> construirEscolha()
            Formatos.LIVRE -> construirTexto(obrigatorio = true)
        }
        if (inq.comentario && inq.formato != Formatos.LIVRE) construirTexto(obrigatorio = false)
        construirEnviar()
        atualizar()
    }

    /* ------------------------------------------------------------ partes */

    private fun construirCabecalho() {
        val linha = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val pergunta = TextView(context).apply {
            text = inq.pergunta(ingles)
            setTextColor(tema.corTexto)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = letra(Typeface.BOLD)
            setPadding(0, dp(8), dp(8), dp(4))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        linha.addView(pergunta, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val fechar = TextView(context).apply {
            // O sinal de multiplicação, e não uma imagem: um recurso gráfico era um
            // ficheiro a mais no pacote de quem nos instala.
            text = "✕"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(comAlfa(tema.corTexto, 0xB3))
            contentDescription = t("Fechar o inquérito", "Close the survey")
            isClickable = true
            isFocusable = true
            papel(this, "android.widget.Button")
            setOnClickListener { Seguranca.executar("inqueritos.fechar") { aoFechar() } }
        }
        linha.addView(fechar, LayoutParams(dp(TOQUE_MINIMO_DP), dp(TOQUE_MINIMO_DP)))
        addView(linha, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** Uma escala de notas, partida em linhas quando não cabe com toques de 48 dp. */
    private fun construirEscala(rotuloMinimo: String, rotuloMaximo: String) {
        val escala = inq.escala ?: return
        val valores = escala.toList()
        val largura = larguraUtilDp()
        val porLinhaMaxima = maxOf(1, floor(largura / TOQUE_MINIMO_DP.toFloat()).toInt())
        val linhas = ceil(valores.size / porLinhaMaxima.toFloat()).toInt()
        val porLinha = ceil(valores.size / linhas.toFloat()).toInt()

        for (pedaco in valores.chunked(porLinha)) {
            val linha = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (valor in pedaco) {
                val botao = RadioButton(context).apply {
                    text = valor.toString()
                    gravity = Gravity.CENTER
                    buttonDrawable = null as Drawable?
                    setPadding(0, 0, 0, 0)
                    minHeight = dp(TOQUE_MINIMO_DP)
                    minimumHeight = dp(TOQUE_MINIMO_DP)
                    minWidth = 0
                    minimumWidth = 0
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = letra(Typeface.BOLD)
                    setTextColor(
                        ColorStateList(
                            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                            intArrayOf(contraste(tema.corPrimaria), tema.corTexto),
                        ),
                    )
                    background = fundoDaNota()
                    contentDescription = descricaoDaNota(valor, escala, rotuloMinimo, rotuloMaximo)
                    setOnClickListener { Seguranca.executar("inqueritos.nota") { escolherNota(valor) } }
                }
                notas += botao
                linha.addView(botao, LayoutParams(0, dp(TOQUE_MINIMO_DP), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            // A última linha fica com as células da mesma largura das outras: sem os
            // espaços vazios, a nota 10 seria o dobro da nota 3.
            repeat(porLinha - pedaco.size) {
                linha.addView(View(context), LayoutParams(0, dp(TOQUE_MINIMO_DP), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            }
            addView(linha, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        val rotulos = LinearLayout(context).apply { orientation = HORIZONTAL }
        rotulos.addView(rotulo(rotuloMinimo, Gravity.START), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        rotulos.addView(rotulo(rotuloMaximo, Gravity.END), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // Os rótulos das pontas já estão na descrição de cada nota: lidos outra vez
        // soltos, a pessoa ouvia-os duas vezes.
        rotulos.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(rotulos, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun descricaoDaNota(valor: Int, escala: IntRange, minimo: String, maximo: String): String {
        val base = if (escala.first == 0) {
            t("$valor, numa escala de 0 a ${escala.last}", "$valor, on a scale of 0 to ${escala.last}")
        } else {
            t("$valor de ${escala.last}", "$valor of ${escala.last}")
        }
        return when (valor) {
            escala.first -> "$base, ${minimo.lowercase()}"
            escala.last -> "$base, ${maximo.lowercase()}"
            else -> base
        }
    }

    private fun rotulo(texto: String, gravidade: Int) = TextView(context).apply {
        text = texto
        gravity = gravidade
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(comAlfa(tema.corTexto, 0xB3))
        typeface = letra()
        setPadding(dp(2), dp(2), dp(2), dp(4))
    }

    private fun fundoDaNota(): Drawable {
        val raio = minOf(tema.cantosPx, 12) * densidade
        val marcada = GradientDrawable().apply {
            setColor(tema.corPrimaria)
            cornerRadius = raio
        }
        val normal = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = raio
            setStroke(dp(1), comAlfa(tema.corTexto, 0x40))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), marcada)
            addState(intArrayOf(), normal)
        }
    }

    private fun escolherNota(valor: Int) {
        nota = valor
        for (b in notas) b.isChecked = b.text.toString() == valor.toString()
        atualizar()
    }

    private fun construirEscolha() {
        val cor = ColorStateList.valueOf(tema.corPrimaria)
        if (inq.multipla) {
            for (op in inq.opcoes) {
                addView(CheckBox(context).apply {
                    text = op.texto(ingles)
                    setTextColor(tema.corTexto)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = letra()
                    buttonTintList = cor
                    minHeight = dp(TOQUE_MINIMO_DP)
                    minimumHeight = dp(TOQUE_MINIMO_DP)
                    setOnCheckedChangeListener { _, marcada ->
                        Seguranca.executar("inqueritos.escolha") {
                            if (marcada) escolhidas += op.chave else escolhidas -= op.chave
                            atualizar()
                        }
                    }
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        } else {
            val grupo = RadioGroup(context).apply { orientation = VERTICAL }
            for (op in inq.opcoes) {
                grupo.addView(RadioButton(context).apply {
                    id = View.generateViewId()
                    text = op.texto(ingles)
                    setTextColor(tema.corTexto)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = letra()
                    buttonTintList = cor
                    minHeight = dp(TOQUE_MINIMO_DP)
                    minimumHeight = dp(TOQUE_MINIMO_DP)
                    setOnCheckedChangeListener { _, marcada ->
                        Seguranca.executar("inqueritos.escolha") {
                            if (marcada) {
                                escolhidas.clear()
                                escolhidas += op.chave
                            }
                            atualizar()
                        }
                    }
                }, RadioGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            addView(grupo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun construirTexto(obrigatorio: Boolean) {
        val campo = EditText(context).apply {
            hint = if (obrigatorio) t("Escreva a sua resposta", "Write your answer") else t("Comentário (opcional)", "Comment (optional)")
            setTextColor(tema.corTexto)
            setHintTextColor(comAlfa(tema.corTexto, 0x8C))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = letra()
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            maxLines = 4
            minHeight = dp(TOQUE_MINIMO_DP)
            minimumHeight = dp(TOQUE_MINIMO_DP)
            filters = arrayOf(InputFilter.LengthFilter(Corpos.LIMITE_DO_COMENTARIO))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = minOf(tema.cantosPx, 12) * densidade
                setStroke(dp(1), comAlfa(tema.corTexto, 0x40))
            }
            // O preenchimento automático oferecia o nome, o correio e o cartão guardados
            // no telemóvel para dentro de uma opinião. Não é um campo de dados pessoais,
            // e diz-se isso ao sistema.
            if (Build.VERSION.SDK_INT >= 26) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    Seguranca.executar("inqueritos.texto") { atualizar() }
                }
            })
        }
        campoTexto = campo
        addView(campo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun construirEnviar() {
        enviar = TextView(context).apply {
            text = t("Enviar", "Send")
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = letra(Typeface.BOLD)
            setTextColor(contraste(tema.corPrimaria))
            minHeight = dp(TOQUE_MINIMO_DP)
            minimumHeight = dp(TOQUE_MINIMO_DP)
            background = GradientDrawable().apply {
                setColor(tema.corPrimaria)
                cornerRadius = minOf(tema.cantosPx, 24) * densidade
            }
            isClickable = true
            isFocusable = true
            papel(this, "android.widget.Button")
            setOnClickListener { Seguranca.executar("inqueritos.enviar") { submeter() } }
        }
        addView(enviar, LayoutParams(LayoutParams.MATCH_PARENT, dp(TOQUE_MINIMO_DP)).apply { topMargin = dp(12) })
    }

    /** Declara a quem lê o ecrã o que a vista é, quando a classe dela não o diz. */
    private fun papel(v: View, classe: String) {
        v.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = classe
            }
        }
    }

    /* ----------------------------------------------------------- estado */

    private fun textoAtual(): String = campoTexto?.text?.toString()?.trim().orEmpty()

    /** A resposta está completa: é o que liga o botão de enviar. */
    fun completa(): Boolean = when (inq.formato) {
        Formatos.ESFORCO, Formatos.SATISFACAO, Formatos.RECOMENDACAO -> nota != null
        Formatos.ESCOLHA -> escolhidas.isNotEmpty()
        Formatos.LIVRE -> textoAtual().isNotEmpty()
        else -> false
    }

    private fun atualizar() {
        if (!::enviar.isInitialized) return
        val pode = completa() && !respondido
        enviar.isEnabled = pode
        enviar.alpha = if (pode) 1f else 0.45f
    }

    private fun submeter() {
        if (!completa() || respondido) return
        respondido = true
        val resposta = Resposta(
            nota = if (inq.escala != null) nota else null,
            escolhas = if (inq.formato == Formatos.ESCOLHA) escolhidas.toList() else emptyList(),
            comentario = campoTexto?.text?.toString().orEmpty(),
            ocorridaEm = agora(),
        )
        esconderTeclado()
        agradecer()
        aoResponder(resposta)
    }

    private fun esconderTeclado() = Seguranca.executar("inqueritos.teclado") {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    /** Troca o conteúdo pelo agradecimento, que quem lê o ecrã ouve sem ter de ir lá. */
    private fun agradecer() {
        removeAllViews()
        campoTexto = null
        addView(TextView(context).apply {
            text = t("Obrigado pela sua resposta.", "Thank you for your answer.")
            setTextColor(tema.corTexto)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = letra(Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(TOQUE_MINIMO_DP)
            setPadding(0, dp(12), 0, dp(4))
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /* --------------------------------------------- largura e barras do sistema */

    private fun larguraUtilDp(): Float {
        val ecraDp = resources.displayMetrics.widthPixels / densidade
        return minOf(ecraDp - 2 * MARGEM_DP, LARGURA_MAXIMA_DP.toFloat()) - 2 * PADDING_DP
    }

    /**
     * Mantém o cartão acima da barra de navegação e do teclado.
     *
     * Com a aplicação desenhada de ponta a ponta, que é o que o Android 15 impõe, o
     * contentor da atividade passa por baixo das barras do sistema, e o cartão ficava
     * meio escondido debaixo da navegação. A conta é "quanto do contentor fica debaixo
     * das barras", e não "quanto medem as barras": numa aplicação que ainda não é de
     * ponta a ponta o contentor já acaba acima delas, e somar a altura das barras
     * afastava o cartão do fundo sem razão.
     */
    private val aoDispor = ViewTreeObserver.OnGlobalLayoutListener {
        Seguranca.executar("inqueritos.margem") { ajustarMargem() }
    }

    private fun ajustarMargem() {
        val parametros = layoutParams as? MarginLayoutParams ?: return
        val raiz = rootView ?: return
        val pai = parent as? View ?: return
        val insets = raiz.rootWindowInsets
        val baixo = when {
            insets == null -> 0
            Build.VERSION.SDK_INT >= 30 ->
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime()).bottom
            else -> @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        val pos = IntArray(2)
        pai.getLocationInWindow(pos)
        val sobreposto = (pos[1] + pai.height - (raiz.height - baixo)).coerceAtLeast(0)
        val margem = dp(MARGEM_DP) + sobreposto
        if (parametros.bottomMargin != margem) {
            parametros.bottomMargin = margem
            layoutParams = parametros
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Seguranca.executar("inqueritos.ligar") {
            viewTreeObserver.addOnGlobalLayoutListener(aoDispor)
            ajustarMargem()
        }
    }

    override fun onDetachedFromWindow() {
        Seguranca.executar("inqueritos.desligar") {
            viewTreeObserver.removeOnGlobalLayoutListener(aoDispor)
        }
        super.onDetachedFromWindow()
    }

    /** Os parâmetros com que o cartão entra no contentor da atividade. */
    fun parametros(): ViewGroup.LayoutParams {
        val ecraDp = resources.displayMetrics.widthPixels / densidade
        val largura = if (ecraDp - 2 * MARGEM_DP > LARGURA_MAXIMA_DP) dp(LARGURA_MAXIMA_DP) else ViewGroup.LayoutParams.MATCH_PARENT
        return android.widget.FrameLayout.LayoutParams(
            largura, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { setMargins(dp(MARGEM_DP), dp(MARGEM_DP), dp(MARGEM_DP), dp(MARGEM_DP)) }
    }

    /* ------------------------------------------------------ para os ensaios */

    internal fun notaParaEnsaio(valor: Int) = escolherNota(valor)
    internal fun enviarParaEnsaio() = enviar.performClick()
    internal fun campoParaEnsaio(): EditText? = campoTexto
}

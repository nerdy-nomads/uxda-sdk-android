package io.uxea.sdk.identidade

import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.EditText
import android.widget.TextView

/**
 * Identidade estável de elementos, em vistas clássicas. RF-CAP-05, ADR 0003.
 *
 * A cadeia é a mesma da web, e a serialização também, porque **quem decide a
 * identidade é o servidor**: ele recebe os cinco sinais e reconcilia contra o que
 * já conhece do projeto. Se o telemóvel mandasse outro formato, o mesmo botão
 * seria dois elementos conforme o canal, e a comparação entre canais morria.
 *
 * O que muda em Android, e é preciso dizer:
 *
 *  - **o sinal do destino quase não existe.** Uma vista não tem `href`. Só há
 *    destino quando alguém o declara (`android:tag="uxea:destino=/checkout"`) ou
 *    quando o texto traz uma ligação. A cadeia fica com quatro sinais na prática,
 *    e a taxa de sobrevivência mede o efeito disso em vez de o adivinhar;
 *  - **o identificador de recurso é mais forte do que o `data-testid` da web**,
 *    porque quase toda a gente o põe, e não por virtude: é preciso para escrever
 *    o esquema.
 *
 * **Nunca lê o que a pessoa escreveu.** Num `EditText` lê a dica e a descrição de
 * conteúdo, que são texto da aplicação, e nunca o `text`, que é da pessoa.
 */
object Elemento {

    data class Sinais(
        val testid: String? = null,
        val caminho: String? = null,
        val rotulo: String? = null,
        val destino: String? = null,
        val papel: String? = null,
    ) {
        val vazio: Boolean get() = listOfNotNull(testid, caminho, rotulo, destino, papel).isEmpty()
    }

    /** Contentores que só servem para dispor: entram no caminho como a web ignora `div`. */
    private val INVOLUCROS = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout", "ConstraintLayout",
        "CoordinatorLayout", "NestedScrollView", "ScrollView", "FitWindowsLinearLayout",
        "ContentFrameLayout", "ActionBarOverlayLayout", "ViewStubCompat", "FragmentContainerView",
    )

    fun acionavel(v: View): Boolean {
        if (!v.isShown) return false
        if (v is EditText || v is Checkable) return true
        if (v.isClickable || v.isLongClickable || v.isFocusableInTouchMode) return true
        return v is TextView && v.isFocusable
    }

    fun sinalTestId(v: View): String? {
        (v.tag as? String)?.let { t ->
            if (t.startsWith("uxea:")) return t.removePrefix("uxea:").substringBefore("=").let { chave ->
                if (chave == "destino") null else t.removePrefix("uxea:")
            }
            // O chão vale aqui (cartão 18.2): uma etiqueta escrita com um valor do cliente
            // lá dentro é um identificador direto na vista. O par do web apanhou-o.
            return "tag=${Mascara.chao(t)}"
        }
        if (v.id == View.NO_ID) return null
        return try {
            val nome = v.resources.getResourceEntryName(v.id)
            if (nome.isNullOrEmpty()) null else "id=$nome"
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Caminho na árvore, sem os invólucros de disposição e com a posição entre
     * irmãos do mesmo tipo. É o sinal que se parte quando alguém acrescenta um
     * contentor, e por isso a reconciliação compara também a cauda.
     */
    fun sinalCaminho(v: View): String {
        val partes = ArrayList<String>(8)
        var atual: View? = v
        var profundidade = 0
        while (atual != null && profundidade < 24) {
            val nome = atual.javaClass.simpleName.ifEmpty { "View" }
            if (nome !in INVOLUCROS) {
                val pai = atual.parent as? ViewGroup
                val indice = if (pai == null) 0 else {
                    var n = 0
                    for (i in 0 until pai.childCount) {
                        val filho = pai.getChildAt(i)
                        if (filho === atual) break
                        if (filho.javaClass == atual.javaClass) n++
                    }
                    n
                }
                partes.add(if (indice > 0) "$nome[$indice]" else nome)
            }
            atual = atual.parent as? View
            profundidade++
        }
        return partes.asReversed().joinToString(">")
    }

    /**
     * Texto **da aplicação**, nunca da pessoa. Num campo de escrita lê a dica; num
     * botão ou etiqueta lê o texto; e em qualquer um lê a descrição de conteúdo.
     */
    fun textoVisivel(v: View): String? {
        val bruto = when {
            v is EditText -> v.hint?.toString() ?: v.contentDescription?.toString()
            v is TextView -> v.text?.toString()?.ifBlank { null } ?: v.contentDescription?.toString()
            else -> v.contentDescription?.toString()
        }
        return bruto?.trim()?.ifEmpty { null }?.take(120)
    }

    fun sinalRotulo(v: View): String? = textoVisivel(v)?.let { Mascara.resumoDe(it) }

    /** Só existe quando alguém o declara, ou quando o texto traz uma ligação. */
    fun sinalDestino(v: View): String? {
        (v.tag as? String)?.let { t ->
            if (t.startsWith("uxea:destino=")) return normalizarDestino(t.removePrefix("uxea:destino="))
        }
        if (v is TextView) {
            val texto = v.text
            if (texto is android.text.Spanned) {
                val ligacoes = texto.getSpans(0, texto.length, android.text.style.URLSpan::class.java)
                if (ligacoes.isNotEmpty()) return normalizarDestino(ligacoes[0].url)
            }
        }
        return null
    }

    /** `/pedidos/8412` vira `/pedidos/{numero}`, para agregar em vez de fragmentar. */
    fun normalizarDestino(bruto: String?): String? {
        if (bruto.isNullOrBlank()) return null
        val semEsquema = bruto.substringAfter("://").let { if (it == bruto) bruto else it.substringAfter("/", "") }
        val caminho = ("/" + semEsquema.substringBefore("?").substringBefore("#").trimStart('/'))
        val partes = caminho.split("/").map { p ->
            when {
                p.isEmpty() -> p
                Regex("""^\d+$""").matches(p) -> "{numero}"
                Regex("""^[0-9a-fA-F-]{16,}$""").matches(p) -> "{id}"
                Regex("""^[A-Za-z]{0,3}\d[\dA-Za-z]{7,}$""").matches(p) -> "{id}"
                // E o correio, os contactos e os números com separadores (cartão 18.2):
                // `/pagar/+244923000111` é uma pessoa no caminho.
                p.contains('@') || Mascara.chao(p) != p || p.filter { it.isDigit() }.length >= 6 -> "{id}"
                else -> p
            }
        }
        return partes.joinToString("/").take(160)
    }

    fun papelDe(v: View): String = when {
        v is EditText -> "campo"
        v is Checkable -> "opcao"
        v is android.widget.Button -> "botao"
        v is android.widget.ImageView && v.isClickable -> "icone"
        v is TextView && v.isClickable -> "ligacao"
        v.isClickable -> "acionavel"
        else -> v.javaClass.simpleName.lowercase()
    }

    fun sinalPapel(v: View): String {
        val papel = papelDe(v)
        val pai = v.parent as? ViewGroup ?: return "$papel#0"
        var n = 0
        for (i in 0 until pai.childCount) {
            val filho = pai.getChildAt(i)
            if (filho === v) break
            if (papelDe(filho) == papel) n++
        }
        return "$papel#$n"
    }

    fun sinais(v: View): Sinais = Sinais(
        testid = sinalTestId(v),
        caminho = sinalCaminho(v).ifEmpty { null },
        rotulo = sinalRotulo(v),
        destino = sinalDestino(v),
        papel = sinalPapel(v),
    )

    /** A fonte é o sinal mais forte que existe, e vai no `f=` da serialização. */
    fun fonteDe(s: Sinais): String = when {
        s.testid != null -> "testid"
        s.destino != null -> "destino"
        s.rotulo != null -> "rotulo"
        s.caminho != null -> "caminho"
        else -> "papel"
    }

    private val LIMITE = mapOf(
        "t" to 80, "c" to 200, "r" to 32, "d" to 120, "p" to 40,
    )

    private fun limpar(v: String, max: Int) = v.replace(Regex("""[|\n\r]"""), "_").take(max)

    /**
     * O formato que a ingestão reconcilia, e é **o mesmo do SDK web**:
     * `v1|f=..|t=..|c=..|r=..|d=..|p=..`, dentro dos 512 caracteres do esquema.
     */
    fun serializar(s: Sinais): String {
        if (s.vazio) return ""
        val partes = ArrayList<String>(7)
        partes.add("v1")
        partes.add("f=" + fonteDe(s))
        s.testid?.let { partes.add("t=" + limpar(it, LIMITE["t"]!!)) }
        s.caminho?.let { partes.add("c=" + limpar(it, LIMITE["c"]!!)) }
        s.rotulo?.let { partes.add("r=" + limpar(it, LIMITE["r"]!!)) }
        s.destino?.let { partes.add("d=" + limpar(it, LIMITE["d"]!!)) }
        s.papel?.let { partes.add("p=" + limpar(it, LIMITE["p"]!!)) }
        return partes.joinToString("|").take(512)
    }

    fun chaveDe(v: View): String = serializar(sinais(v))
}

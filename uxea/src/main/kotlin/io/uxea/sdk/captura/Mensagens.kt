package io.uxea.sdk.captura

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import io.uxea.sdk.Ids
import io.uxea.sdk.Seguranca
import io.uxea.sdk.Tipos
import io.uxea.sdk.identidade.Elemento
import io.uxea.sdk.identidade.Mascara

/**
 * Mensagens de sistema apresentadas ao utilizador. Cartões 5.1 e 5.2,
 * RF-MSG-01 a RF-MSG-05 e RNF-PRI-04.
 *
 * É o irmão do `mensagens.ts` do SDK web, e produz **os mesmos eventos com os
 * mesmos nomes**: a mesma mensagem numa aplicação móvel e num sítio da mesma
 * organização tem de contar como uma entrada só no catálogo (RF-ADM-10).
 *
 * Onde o Android obriga a fazer diferente da web, e porquê:
 *
 * | | Na web | Aqui |
 * |---|---|---|
 * | Deteção | `MutationObserver` sobre o documento | varrimento da árvore, travado no tempo, a cada disposição |
 * | Chave | `data-uxea-mensagem` | `View.setTag("uxea:mensagem=<chave>")`, ou o nome do recurso |
 * | Validação | `invalid-feedback` ao lado do campo | `setError` do `TextView` e do `TextInputLayout` |
 *
 * **O varrimento é travado de propósito.** Uma aplicação faz dezenas de disposições
 * por segundo, e percorrer a árvore em todas custava mais do que o orçamento
 * inteiro do fio principal (RNF-SDK-02). Corre no máximo uma vez a cada 250 ms,
 * com a profundidade e o número de vistas limitados, e o que já foi visto não se
 * volta a contar.
 *
 * **O texto nunca vem de um campo de escrita.** O `Elemento.textoVisivel` lê a
 * dica de um `EditText` e nunca o valor, e este módulo salta os campos por
 * completo ao juntar o texto de um contentor.
 */
internal class Mensagens(
    private val emitir: (tipo: String, elemento: String?, extras: Map<String, String>, propriedades: Map<String, Any>?) -> Unit,
    private val agora: () -> Long,
    /** O passo em que a tentativa vai, para o contexto do RF-MSG-05. */
    private val passo: () -> String = { "" },
    /** A lista explícita de permissões do RNF-PRI-04. Vazia por omissão. */
    private val exposta: (String) -> Boolean = { false },
) {

    /** A mesma mensagem que reaparece de segundo a segundo é uma mensagem, e não vinte. */
    private val vistas = HashMap<String, Long>()

    /** As vistas já contadas. Chave fraca: uma tela destruída não fica aqui presa. */
    private val jaVistas = java.util.WeakHashMap<View, Boolean>()

    /** O nome do recurso custa uma consulta ao `Resources`, e repete-se muito. */
    private val nomesDeId = HashMap<Int, String>()

    private var ultimaVarredura = 0L

    /**
     * A primeira varredura de cada ecrã corre sempre.
     *
     * Sem esta bandeira, o travão comparava contra um zero e a primeira varredura
     * era ela própria travada: um ecrã que abre já com a mensagem lá dentro nunca
     * era visto, e é o caso mais comum de todos (um erro devolvido pelo passo
     * anterior).
     */
    private var primeiraVarredura = true

    companion object {
        private const val INTERVALO_MS = 250L
        private const val JANELA_REPETIDA_MS = 1500L
        private const val PROFUNDIDADE = 8
        private const val VISTAS_POR_VARREDURA = 120
        private const val MAX_TEXTO = 240

        /** O que faz de uma vista uma mensagem, e não um pedaço qualquer de ecrã. */
        private val NOMES_DE_MENSAGEM = Regex(
            "(toast|snackbar|alert|notification|notificacao|flash|message|mensagem|erro|error|aviso|warning|success|sucesso|banner|callout)",
            RegexOption.IGNORE_CASE,
        )

        /** A ordem importa: `erro` ganha a `info`, e um `alert_error` tem os dois. */
        private val PISTAS: List<Pair<Regex, String>> = listOf(
            Regex("(erro|error|danger|invalid|invalido|fail|failure|critical|critico)", RegexOption.IGNORE_CASE) to "erro",
            Regex("(aviso|warn|warning|caution|alerta)", RegexOption.IGNORE_CASE) to "aviso",
            Regex("(sucesso|success|confirm|done)", RegexOption.IGNORE_CASE) to "sucesso",
            Regex("(info|informa|note|notice|hint|dica)", RegexOption.IGNORE_CASE) to "info",
        )

        internal const val TAG_MENSAGEM = "uxea:mensagem="
    }

    /* ------------------------------------------------------------ deteção */

    private fun nomeDoId(v: View): String {
        if (v.id == View.NO_ID) return ""
        nomesDeId[v.id]?.let { return it }
        val nome = try {
            v.resources?.getResourceEntryName(v.id) ?: ""
        } catch (_: Throwable) {
            ""
        }
        nomesDeId[v.id] = nome
        return nome
    }

    /** A etiqueta explícita, que é o equivalente do `data-uxea-mensagem` da web. */
    private fun chaveDeclarada(v: View): String? =
        (v.tag as? String)?.takeIf { it.startsWith(TAG_MENSAGEM) }?.removePrefix(TAG_MENSAGEM)?.ifEmpty { null }

    internal fun ehMensagem(v: View): Boolean {
        if (chaveDeclarada(v) != null) return true
        val classe = v.javaClass.simpleName
        if (NOMES_DE_MENSAGEM.containsMatchIn(classe)) return true
        return NOMES_DE_MENSAGEM.containsMatchIn(nomeDoId(v))
    }

    internal fun tipoDe(v: View): String {
        val pistas = "${v.javaClass.simpleName} ${nomeDoId(v)}"
        for ((re, tipo) in PISTAS) if (re.containsMatchIn(pistas)) return tipo
        // Um `Snackbar` ou um `Toast` sem mais nada informa e não interrompe; um
        // diálogo de alerta é o contrário, e as aplicações usam-no para o que
        // corre mal.
        if (v.javaClass.simpleName.contains("Alert", ignoreCase = true)) return "erro"
        return "info"
    }

    /**
     * O texto da mensagem, **sem nunca ler um campo de escrita**.
     *
     * Um contentor de mensagem costuma ter o título e o corpo em dois `TextView`
     * separados, e o botão de ação num terceiro. Juntam-se os dois primeiros e
     * salta-se por cima de qualquer `EditText`, que é onde vive o que a pessoa
     * escreveu.
     */
    internal fun textoDe(v: View, profundidade: Int = 3): String {
        if (v is EditText || v is VistaDoSdk) return ""
        if (v is TextView) return v.text?.toString()?.trim() ?: ""
        if (v !is ViewGroup || profundidade <= 0) return ""
        val partes = StringBuilder()
        for (i in 0 until v.childCount) {
            val filho = v.getChildAt(i)
            if (filho.visibility != View.VISIBLE) continue
            val t = textoDe(filho, profundidade - 1)
            if (t.isNotEmpty()) {
                if (partes.isNotEmpty()) partes.append(' ')
                partes.append(t)
            }
            if (partes.length > 600) break
        }
        return partes.toString()
    }

    /* ------------------------------------------------------------ emissão */

    private fun jaContou(chave: String): Boolean {
        val n = agora()
        val antes = vistas[chave]
        if (antes != null && n - antes < JANELA_REPETIDA_MS) return true
        vistas[chave] = n
        if (vistas.size > 200) vistas.entries.removeAll { n - it.value > 60_000 }
        return false
    }

    /**
     * O evento, tal como sai. É o mesmo formato do SDK web, campo a campo: a chave
     * e a classe em colunas próprias, e o resto do contexto nas propriedades.
     */
    private fun emitirMensagem(
        chave: String?,
        texto: String?,
        tipo: String,
        classe: String?,
        elemento: String?,
        campo: String?,
        origem: String,
        visivel: Boolean,
        extra: Map<String, Any> = emptyMap(),
    ) {
        val props = HashMap<String, Any>(extra)
        props["origem_mensagem"] = origem
        props["visivel"] = visivel
        if (classe != null) props["classe_erro"] = classe
        // A chave do elemento, inteira: 512 como o `element_key`.
        if (campo != null) props["campo_associado"] = campo.take(512)
        passo().takeIf { it.isNotEmpty() }?.let { props["passo"] = it.take(64) }

        var chaveFinal = chave
        var textoFinal: String? = null
        if (!texto.isNullOrEmpty()) {
            val mascarada = Mascara.mascararMensagem(texto)
            Mascara.esqueletoDeMensagem(mascarada).takeIf { it.isNotEmpty() }?.let {
                props["grupo_mensagem"] = Ids.resumo(it)
            }
            if (chaveFinal.isNullOrEmpty()) {
                chaveFinal = "txt_" + Ids.resumo(Mascara.normalizarTexto(mascarada))
            }
            // Exposta ou não, o `mascarar` corre **sempre**: a lista de permissões
            // levanta a mascaragem estrutural (nomes e citações) e nunca o chão.
            // Números, identificadores e correio electrónico saem sempre
            // mascarados, autorize quem autorizar, e a ingestão recusa o evento
            // que os traga.
            textoFinal = (if (exposta(chaveFinal)) Mascara.mascarar(texto) else mascarada).take(MAX_TEXTO)
        }
        val k = chaveFinal ?: return
        if (jaContou("$k|$visivel")) return

        val extras = HashMap<String, String>(2)
        extras["message_key"] = k.take(256)
        extras["message_kind"] = tipo
        textoFinal?.let { extras["message_text_masked"] = it }
        emitir(Tipos.MENSAGEM, elemento, extras, props)
    }

    /* ------------------------------------------------- o que aparece no ecrã */

    /**
     * Um contentor de mensagens **não é uma mensagem**.
     *
     * O invólucro onde a aplicação empilha os avisos chama-se, quase sempre,
     * `avisos` ou `mensagens`, e por isso passa no `ehMensagem`; o texto dele é a
     * soma dos filhos. Sem esta pergunta, cada mensagem contava duas vezes, uma
     * pelo filho e outra pelo pai, e a segunda com a classificação do invólucro.
     *
     * Apanhado no ensaio em telemóvel do cartão 5.1: o mesmo "Saldo insuficiente"
     * chegou ao armazenamento duas vezes, uma como `info` e outra como `aviso`.
     * **A mensagem é sempre a mais funda**, porque é a que alguém escreveu.
     */
    private fun temMensagemDentro(v: View, profundidade: Int = 4): Boolean {
        if (v !is ViewGroup || profundidade <= 0) return false
        for (i in 0 until v.childCount) {
            val filho = v.getChildAt(i)
            if (filho.visibility != View.VISIBLE || filho is VistaDoSdk) continue
            if (ehMensagem(filho)) return true
            if (temMensagemDentro(filho, profundidade - 1)) return true
        }
        return false
    }

    /** Uma vista que apareceu no ecrã e que é uma mensagem. */
    private fun analisar(v: View) {
        if (jaVistas.containsKey(v)) return
        if (!ehMensagem(v)) return
        if (temMensagemDentro(v)) return
        val chave = chaveDeclarada(v)
        val texto = textoDe(v).take(600)
        // Sem chave e sem texto não há mensagem nenhuma: é um invólucro vazio à
        // espera de ser preenchido, e uma aplicação tem dezenas deles no ecrã.
        if (chave.isNullOrEmpty() && texto.isEmpty()) return
        jaVistas[v] = true

        val tipo = tipoDe(v)
        val campo = campoDentro(v)
        emitirMensagem(
            chave = chave,
            texto = if (chave.isNullOrEmpty()) texto else null,
            tipo = tipo,
            classe = if (tipo != "erro") null else if (campo != null) "validacao" else "operacao",
            elemento = Elemento.chaveDe(v).ifEmpty { null },
            campo = campo?.let { Elemento.chaveDe(it).ifEmpty { null } },
            origem = if (chave.isNullOrEmpty()) "texto" else "chave",
            visivel = true,
        )
    }

    /**
     * O campo a que a mensagem pertence, quando pertence a algum.
     *
     * Como na web, com a mesma guarda: **um** campo e não vários. Um invólucro com
     * três campos é uma secção, e a mensagem que lá vive é da secção.
     */
    private fun campoDentro(v: View): View? {
        val pai = v.parent as? ViewGroup ?: return null
        var achado: View? = null
        var quantos = 0
        for (i in 0 until pai.childCount) {
            val filho = pai.getChildAt(i)
            if (filho is EditText) {
                quantos++
                achado = filho
            }
        }
        return if (quantos == 1) achado else null
    }

    /**
     * Varre a árvore à procura de mensagens novas. **Travado no tempo e no
     * tamanho**: uma aplicação dispõe dezenas de vezes por segundo, e sem os dois
     * limites isto sozinho estourava o orçamento do fio principal.
     */
    fun varrer(raiz: View?) {
        if (raiz == null) return
        val n = agora()
        if (!primeiraVarredura && n - ultimaVarredura < INTERVALO_MS) return
        primeiraVarredura = false
        ultimaVarredura = n
        Seguranca.executar("captura.mensagens") {
            var restantes = VISTAS_POR_VARREDURA
            fun percorrer(v: View, nivel: Int) {
                if (restantes <= 0 || nivel > PROFUNDIDADE) return
                // Uma vista escondida não mostra mensagem nenhuma a ninguém, e o
                // ramo debaixo dela também não.
                if (v.visibility != View.VISIBLE) return
                // A pergunta e o agradecimento do inquérito são texto nosso, e não
                // mensagens da aplicação: no catálogo do RF-MSG-09 seriam uma
                // entrada que o cliente nunca escreveu.
                if (v is VistaDoSdk) return
                restantes--
                analisar(v)
                if (v is ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i), nivel + 1)
            }
            percorrer(raiz, 0)
        }
    }

    /**
     * O `setError` de um campo, que é o mecanismo de validação da plataforma.
     *
     * Aqui a mensagem **é lida**, ao contrário do evento `erro`, que só guarda que
     * campo falhou. E é lida porque agora há onde a pôr sem risco: sai mascarada
     * pelo mesmo caminho de todas as outras, e é isso que a torna contável no
     * catálogo do RF-MSG-09 em vez de aparecer como um `validacao_nativa` sem
     * conteúdo nenhum, igual para todos os campos do produto.
     */
    fun erroDeCampo(campo: View, mensagem: CharSequence?) {
        val texto = mensagem?.toString()?.trim().orEmpty()
        if (texto.isEmpty()) return
        val chaveCampo = Elemento.chaveDe(campo).ifEmpty { null }
        emitirMensagem(
            chave = null,
            texto = texto,
            tipo = "erro",
            classe = "validacao",
            elemento = chaveCampo,
            campo = chaveCampo,
            origem = "texto",
            visivel = true,
        )
    }

    /** A API pública: a aplicação declara o que o SDK não vê sozinho. */
    fun declarar(chave: String, tipo: String, operacao: String?) {
        if (chave.isEmpty()) return
        emitirMensagem(
            chave = chave, texto = null, tipo = tipo,
            classe = if (tipo == "erro") "operacao" else null,
            elemento = null, campo = null, origem = "declarada", visivel = true,
            extra = if (operacao != null) mapOf("operacao" to operacao.take(32)) else emptyMap(),
        )
    }

    /** Um erro que ninguém viu no ecrã (RF-MSG-06). */
    fun tecnico(chave: String, propriedades: Map<String, Any>, elemento: String? = null) {
        emitirMensagem(
            chave = chave, texto = null, tipo = "erro",
            classe = propriedades["classe_erro"] as? String ?: "sistema",
            elemento = elemento, campo = null, origem = "rede", visivel = false,
            extra = propriedades,
        )
    }

    /** Um ecrã novo apaga a memória do que já se viu: a mesma mensagem lá é outra. */
    fun ecraNovo() {
        jaVistas.clear()
        ultimaVarredura = 0L
        primeiraVarredura = true
    }
}

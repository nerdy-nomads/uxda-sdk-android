package io.uxea.sdk.captura

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import io.uxea.sdk.Seguranca
import io.uxea.sdk.Tipos
import io.uxea.sdk.identidade.Elemento

/**
 * Preenchimento, campo a campo, **sem nunca saber o que a pessoa escreveu**.
 * Cartões 4.2 e 4.3, RF-GRA-09 a RF-GRA-19.
 *
 * A regra que atravessa a secção é absoluta: mede-se o comportamento sobre o
 * campo, nunca o conteúdo. O RF-GRA-12 pede a contagem de caracteres introduzidos
 * e apagados, e o documento sublinha o que muita gente lê ao contrário: **isso não
 * exige conhecer um único caractere**.
 *
 * Como é que se conta sem ler: o `TextWatcher` do Android diz, em cada alteração,
 * quantos caracteres saíram (`before`) e quantos entraram (`count`). São dois
 * números. O `CharSequence` com o texto passa ao lado e não é lido em lado nenhum,
 * e a bateria de fuga falha se algum dia passar a ser.
 *
 * **Um evento por campo, emitido no desfoco** (RF-GRA-29). Um evento por tecla
 * multiplicaria o volume por dezenas, e é o risco crítico que o documento nomeia.
 */
internal class Campos(
    private val emitir: (String, String?, Long?, Map<String, Any>?) -> Unit,
    private val agora: () -> Long,
    private val detalhado: () -> Boolean,
    private val essencial: () -> Boolean,
) {
    private class Estado(val chave: String, val ordemPrevista: Int) {
        var ordem = 0
        var visitas = 0
        // Uma bandeira, e não `focadoEm == 0`. O zero é um instante como outro
        // qualquer, e usá-lo como "não focado" é um defeito à espera de um relógio
        // que comece em zero: foi assim que o agregado deixou de sair todo.
        var focado = false
        var focadoEm = 0L
        var escondido = false
        var escondidoEm = 0L
        var ativoMs = 0L
        var fundoMs = 0L
        var hesitacaoMs: Long? = null
        var escritos = 0
        var apagados = 0
        var origem: String? = null
        var comprimento = 0
        var erros = 0
        var resolvido = true
    }

    private val estados = HashMap<Int, Estado>()
    private var ordemSeguinte = 1
    private var aberto: EditText? = null
    private var ultimoComFoco = ""
    private var escondido = false

    /** O último campo com foco antes de sair: é o campo de abandono (RF-GRA-18). */
    fun campoDeAbandono(): String = ultimoComFoco

    private fun estadoDe(v: EditText): Estado = estados.getOrPut(v.hashCode()) {
        Estado(Elemento.chaveDe(v), ordemPrevistaDe(v))
    }

    /** A ordem que o formulário previa, que é contra a qual a efetiva se compara. */
    private fun ordemPrevistaDe(v: EditText): Int = Seguranca.protegido("campos.ordem", 0) {
        val raiz = v.rootView ?: return@protegido 0
        var i = 0
        var achou = 0
        fun percorrer(x: View) {
            // O comentário do inquérito não é um campo do formulário, e contá-lo
            // mudava a ordem prevista de todos os que vêm depois dele.
            if (x is VistaDoSdk) return
            if (x is EditText) {
                i++
                if (x === v) achou = i
            }
            if (x is ViewGroup) for (k in 0 until x.childCount) percorrer(x.getChildAt(k))
        }
        percorrer(raiz)
        achou
    }

    private fun propriedades(e: Estado, estadoNaSubmissao: String? = null, fase: String? = null): Map<String, Any> {
        val p = HashMap<String, Any>()
        p["caracteres_escritos"] = e.escritos
        p["caracteres_apagados"] = e.apagados
        p["regressos"] = (e.visitas - 1).coerceAtLeast(0)
        p["ordem"] = e.ordem
        p["ordem_prevista"] = e.ordemPrevista
        e.hesitacaoMs?.let { p["hesitacao_ms"] = it }
        if (e.fundoMs > 0) p["tempo_fundo_ms"] = e.fundoMs
        e.origem?.let { p["origem"] = it }
        if (e.visitas > 0 && e.comprimento == 0) p["visitado_vazio"] = true
        if (e.erros > 0) p["tentativas_ate_resolver"] = e.erros
        estadoNaSubmissao?.let { p["estado_na_submissao"] = it }
        fase?.let { p["fase"] = it }
        return p
    }

    private fun emitirCampo(e: Estado, estadoNaSubmissao: String? = null, fase: String? = null) {
        if (essencial()) return
        emitir(Tipos.CAMPO, e.chave, e.ativoMs.coerceAtLeast(0), propriedades(e, estadoNaSubmissao, fase))
    }

    /** O comprimento do que lá está. Um número, e nunca o texto. */
    private fun comprimentoDe(v: EditText): Int = Seguranca.protegido("campos.comprimento", 0) {
        v.text?.length ?: 0
    }

    fun entrar(v: EditText) {
        aberto?.let { if (it !== v) sair(it) }
        val e = estadoDe(v)
        e.visitas++
        if (e.ordem == 0) e.ordem = ordemSeguinte++
        e.focado = true
        e.focadoEm = agora()
        e.escondido = escondido
        e.escondidoEm = if (escondido) agora() else 0
        aberto = v
        ultimoComFoco = e.chave
        if (!essencial()) emitir(Tipos.FOCO, e.chave, null, null)
        ouvir(v)
    }

    fun sair(v: EditText) {
        val e = estados[v.hashCode()] ?: return
        if (!e.focado) return
        e.ativoMs += (agora() - e.focadoEm).coerceAtLeast(0)
        e.focado = false
        e.comprimento = comprimentoDe(v)
        emitirCampo(e)
        if (detalhado()) emitir(Tipos.DESFOCO, e.chave, e.ativoMs, null)
        if (aberto === v) aberto = null
    }

    private val ouvidos = HashSet<Int>()

    private fun ouvir(v: EditText) {
        if (!ouvidos.add(v.hashCode())) return
        v.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, i: Int, a: Int, b: Int) = Unit
            override fun afterTextChanged(s: Editable?) = Unit

            /**
             * `before` são os caracteres que saíram e `count` os que entraram. São
             * dois números, e o `s` com o texto passa ao lado sem ser lido.
             */
            override fun onTextChanged(s: CharSequence?, inicio: Int, before: Int, count: Int) {
                Seguranca.executar("campos.texto") {
                    val e = estadoDe(v)
                    if (before > 0) e.apagados += before
                    if (count > 0) {
                        e.escritos += count
                        // Um salto de vários caracteres de uma vez não é escrever:
                        // é colar, ou é o sistema a preencher. A distinção muda a
                        // interpretação de tudo o resto (RF-GRA-13).
                        val o = if (count > 1) "colagem" else "manual"
                        if (e.origem == null || e.origem == "manual") e.origem = o
                    }
                    e.comprimento = (s?.length ?: 0)
                    if (e.hesitacaoMs == null && e.focado) {
                        e.hesitacaoMs = (agora() - e.focadoEm).coerceAtLeast(0)
                        if (detalhado()) emitir(Tipos.TECLA, e.chave, e.hesitacaoMs, null)
                    }
                }
            }
        })
    }

    /** Um erro de validação naquele campo, com a chave da mensagem (RF-GRA-17). */
    fun aoErrar(v: View, chaveDaMensagem: String): Map<String, Any>? {
        if (v !is EditText) return null
        val e = estadoDe(v)
        e.erros++
        e.resolvido = false
        return mapOf("tentativas_ate_resolver" to e.erros)
    }

    /**
     * O retrato de cada campo no momento da submissão (RF-GRA-19), **incluindo os
     * que ninguém chegou a visitar**: é isso que distingue "visitou e deixou vazio"
     * de "nunca lá foi", que o RF-GRA-14 pede e que só aqui se sabe.
     */
    fun aoSubmeter(raiz: View?): Triple<Int, Int, Int> {
        aberto?.let { sair(it) }
        var preenchidos = 0
        var vazios = 0
        var comErro = 0
        fun percorrer(v: View) {
            // O retrato da submissão é do formulário da aplicação. O cartão do
            // inquérito está na mesma árvore e não entra nele (cartão 14.1).
            if (v is VistaDoSdk) return
            if (v is EditText) {
                val e = estadoDe(v)
                e.comprimento = comprimentoDe(v)
                val estado = when {
                    e.erros > 0 && !e.resolvido -> "com_erro_pendente"
                    e.comprimento > 0 -> "preenchido"
                    else -> "vazio"
                }
                when (estado) {
                    "preenchido" -> preenchidos++
                    "vazio" -> vazios++
                    else -> comErro++
                }
                emitirCampo(e, estado, "submissao")
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) percorrer(v.getChildAt(i))
        }
        raiz?.let { Seguranca.executar("campos.submissao") { percorrer(it) } }
        return Triple(preenchidos, vazios, comErro)
    }

    fun esconder() {
        escondido = true
        aberto?.let {
            estados[it.hashCode()]?.let { e ->
                if (e.focado) { e.escondido = true; e.escondidoEm = agora() }
            }
        }
    }

    fun mostrar() {
        escondido = false
        aberto?.let {
            estados[it.hashCode()]?.let { e ->
                if (e.escondido) {
                    e.fundoMs += (agora() - e.escondidoEm).coerceAtLeast(0)
                    e.escondido = false
                }
            }
        }
    }
}

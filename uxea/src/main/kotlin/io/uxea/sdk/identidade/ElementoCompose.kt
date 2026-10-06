package io.uxea.sdk.identidade

import android.view.View
import io.uxea.sdk.Seguranca

/**
 * Identidade de elementos em Compose. RF-CAP-05, cartão 3.3.
 *
 * Em Compose não há vistas: há uma única `AndroidComposeView` com o ecrã inteiro
 * lá dentro. Sem isto, **todos os toques de um ecrã em Compose davam o mesmo
 * elemento**, e o inventário de funcionalidades ficava com uma entrada por ecrã.
 *
 * O que se lê é a **árvore semântica**, que é a mesma coisa que o leitor de ecrã
 * lê e que os testes de interface usam. Duas consequências, e as duas são boas:
 * um ecrã acessível é um ecrã bem identificado, e quem já pôs `testTag` para
 * testar não tem de fazer nada.
 *
 * Tudo aqui é por reflexão, e é de propósito: o Compose entra como dependência de
 * compilação apenas (`compileOnly`), para uma aplicação que não o usa não levar um
 * único byte dele por nossa causa (RNF-SDK-03). E se a versão do Compose mudar os
 * nomes, isto degrada para "não sei" em vez de rebentar (RNF-SDK-01).
 */
object ElementoCompose {

    /** Reconhece a vista que hospeda uma composição, sem depender do Compose. */
    fun ehCompose(v: View): Boolean =
        v.javaClass.name.contains("AndroidComposeView") ||
            interfaces(v.javaClass).any { it.name == "androidx.compose.ui.node.RootForTest" }

    private fun interfaces(c: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        var atual: Class<*>? = c
        while (atual != null) {
            out.addAll(atual.interfaces)
            atual = atual.superclass
        }
        return out
    }

    /**
     * Encontra o nó semântico mais fundo que contém o ponto tocado, e devolve os
     * sinais dele. Devolve `null` quando não há Compose, quando a árvore não é
     * legível, ou quando o toque não caiu em nada com semântica.
     */
    fun sinaisNoPonto(raiz: View, x: Float, y: Float): Elemento.Sinais? =
        Seguranca.protegido("compose.sinais", null) {
            val owner = donoSemantico(raiz) ?: return@protegido null
            // A árvore **fundida**, e não a crua. Um botão em Compose é vários nós:
            // o `testTag` fica no nó do modificador, o texto no nó do `Text`, e o
            // papel no nó clicável. Na árvore crua, o nó mais fundo debaixo do dedo
            // é o texto, e sai de lá sem `testTag` nenhum, que foi exatamente o que
            // se viu no emulador: dois toques em botões diferentes davam duas chaves
            // sem identidade nenhuma. A árvore fundida junta o que o leitor de ecrã
            // também junta, e é a que descreve o elemento como uma pessoa o vê.
            val raizNo = obter(owner, "getRootSemanticsNode")
                ?: obter(owner, "getUnmergedRootSemanticsNode")
                ?: return@protegido null
            val alvo = maisFundoQueContem(raizNo, x, y, 0) ?: return@protegido null
            sinaisDoNo(alvo.no, alvo.caminho)
        }

    private fun donoSemantico(v: View): Any? {
        val m = v.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
        return m?.invoke(v)
    }

    private fun obter(alvo: Any, metodo: String): Any? =
        alvo.javaClass.methods.firstOrNull { it.name == metodo && it.parameterCount == 0 }?.invoke(alvo)

    private class Encontrado(val no: Any, val caminho: List<String>)

    @Suppress("UNCHECKED_CAST")
    private fun filhos(no: Any): List<Any> =
        (obter(no, "getChildren") as? List<Any>) ?: emptyList()

    /**
     * Procura em profundidade e fica com o **mais fundo** que contém o ponto: é o
     * botão, e não o cartão que o rodeia. É a mesma regra do lado da web, onde se
     * sobe do alvo do clique até ao acionável mais próximo.
     */
    private fun maisFundoQueContem(no: Any, x: Float, y: Float, nivel: Int): Encontrado? {
        if (nivel > 24) return null
        if (!contem(no, x, y)) return null
        val meus = filhos(no)
        var melhor: Encontrado? = null
        for ((i, filho) in meus.withIndex()) {
            val achado = maisFundoQueContem(filho, x, y, nivel + 1) ?: continue
            val caminho = listOf(rotuloDeNo(filho, i)) + achado.caminho
            melhor = Encontrado(achado.no, caminho)
        }
        return melhor ?: Encontrado(no, listOf(rotuloDeNo(no, 0)))
    }

    private fun contem(no: Any, x: Float, y: Float): Boolean {
        val limites = obter(no, "getBoundsInWindow") ?: return false
        val esq = campoFloat(limites, "getLeft") ?: return false
        val topo = campoFloat(limites, "getTop") ?: return false
        val dir = campoFloat(limites, "getRight") ?: return false
        val baixo = campoFloat(limites, "getBottom") ?: return false
        return x >= esq && x <= dir && y >= topo && y <= baixo
    }

    private fun campoFloat(alvo: Any, metodo: String): Float? =
        (obter(alvo, metodo) as? Number)?.toFloat()

    private fun rotuloDeNo(no: Any, indice: Int): String {
        val papel = propriedade(no, "Role")?.toString()?.lowercase()
        val nome = papel ?: "no"
        return if (indice > 0) "$nome[$indice]" else nome
    }

    /**
     * Lê uma propriedade semântica pelo nome, sem importar o Compose. O mapa de
     * propriedades é iterável, e a chave sabe dizer o nome dela.
     */
    /** Ligado por `adb shell setprop log.tag.UxeaCompose VERBOSE`, e mais nada. */
    private val depurar: Boolean by lazy {
        Seguranca.protegido("compose.depurar", false) {
            android.util.Log.isLoggable("UxeaCompose", android.util.Log.VERBOSE)
        }
    }

    private fun propriedade(no: Any, nome: String): Any? {
        val config = obter(no, "getConfig")
        if (config == null) {
            if (depurar) android.util.Log.v("UxeaCompose", "sem getConfig em " + no.javaClass.name)
            return null
        }
        val iterador = (config as? Iterable<*>)?.iterator()
        if (iterador == null) {
            if (depurar) android.util.Log.v("UxeaCompose", "config nao iteravel: " + config.javaClass.name)
            return null
        }
        for (entrada in iterador) {
            val chave = entrada?.javaClass?.methods
                ?.firstOrNull { it.name == "getKey" && it.parameterCount == 0 }?.invoke(entrada)
            val nomeChave = chave?.let { obter(it, "getName") }?.toString()
            if (depurar) android.util.Log.v("UxeaCompose", "chave=" + nomeChave + " classe=" + (chave?.javaClass?.name ?: "nula"))
            if (nomeChave == null) continue
            if (nomeChave == nome) {
                return entrada.javaClass.methods
                    .firstOrNull { it.name == "getValue" && it.parameterCount == 0 }?.invoke(entrada)
            }
        }
        return null
    }

    private fun texto(no: Any): String? {
        // `Text` é texto da aplicação. **`EditableText` é o que a pessoa escreveu**,
        // e esse não se lê aqui nem em lado nenhum (RNF-PRI-01).
        val t = propriedade(no, "Text")
        val bruto = when (t) {
            null -> null
            is List<*> -> t.joinToString(" ") { it?.toString().orEmpty() }
            else -> t.toString()
        }
        val descricao = propriedade(no, "ContentDescription")?.let {
            if (it is List<*>) it.joinToString(" ") { x -> x?.toString().orEmpty() } else it.toString()
        }
        return (bruto?.ifBlank { null } ?: descricao?.ifBlank { null })?.trim()?.take(120)
    }

    /** Sobe até encontrar quem tenha a propriedade, como se sobe do ícone ao botão. */
    private fun deSiOuDosPais(no: Any, nome: String, saltos: Int = 4): Any? {
        var atual: Any? = no
        var n = 0
        while (atual != null && n <= saltos) {
            propriedade(atual, nome)?.let { return it }
            atual = obter(atual, "getParent")
            n++
        }
        return null
    }

    private fun sinaisDoNo(no: Any, caminho: List<String>): Elemento.Sinais {
        val testTag = deSiOuDosPais(no, "TestTag")?.toString()
        val papel = deSiOuDosPais(no, "Role")?.toString()?.lowercase()
        val rotulo = texto(no)?.let { Mascara.resumoDe(it) }
        return Elemento.Sinais(
            testid = testTag?.let { "testTag=${Mascara.chao(it)}" },
            caminho = caminho.takeIf { it.isNotEmpty() }?.joinToString(">")?.take(200),
            rotulo = rotulo,
            destino = null,
            papel = (papel ?: "compose") + "#0",
        )
    }
}

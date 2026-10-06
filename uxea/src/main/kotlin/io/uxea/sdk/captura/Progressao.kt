package io.uxea.sdk.captura

import io.uxea.sdk.Terminal
import io.uxea.sdk.Tipos

/**
 * Progressão da tentativa e evento terminal. Cartão 4.4, RF-GRA-20 a RF-GRA-25.
 *
 * A distinção que o documento pede, e que é a razão de este módulo existir, é
 * entre **tempo de espera imposto pelo sistema** e **tempo de decisão do
 * utilizador**. Confundi-los transforma a lentidão do servidor em hesitação da
 * pessoa, e leva a equipa a redesenhar um ecrã que estava bem.
 *
 * **O `expirado` não sai daqui.** Um telemóvel que fecha não sabe que expirou: quem
 * sabe é o motor, ao aplicar o limiar da tarefa (cartão 6.5). A API aceita-o na
 * mesma, para quem quiser marcá-lo por sua conta.
 */
internal class Progressao(
    private val emitir: (String, String?, Long?, Map<String, Any>?) -> Unit,
    private val agora: () -> Long,
    private val essencial: () -> Boolean,
    private val campoDeAbandono: () -> String,
) {
    private var passoAtual = ""
    private var passoDesde = agora()
    private var atividadePendente = false
    private var terminada = false
    private var escondido = false
    private var escondidoEm = 0L

    /** Em que passo a tentativa vai. É o contexto do RF-MSG-05, e não emite nada. */
    fun passoAtual(): String = passoAtual

    fun passo(nome: String) {
        if (essencial() || nome.isEmpty()) return
        val t = agora()
        val props = HashMap<String, Any>()
        props["passo"] = nome.take(64)
        if (passoAtual.isNotEmpty()) props["passo_anterior"] = passoAtual.take(64)
        emitir(Tipos.PASSO, null, if (passoAtual.isNotEmpty()) (t - passoDesde).coerceAtLeast(0) else null, props)
        passoAtual = nome
        passoDesde = t
        atividadePendente = true
        terminada = false
    }

    /**
     * O tempo que o sistema impôs. Sai num evento próprio de propósito: somado ao
     * tempo do passo, ninguém consegue voltar a separá-los depois.
     */
    fun espera(ms: Long) {
        if (essencial() || ms <= 0) return
        emitir(Tipos.ESPERA, null, ms, null)
    }

    fun terminal(estado: String) {
        if (terminada) return
        terminada = true
        atividadePendente = false
        val props = HashMap<String, Any>()
        props["estado"] = estado
        val campo = campoDeAbandono()
        // **512 e não 64.** O que vai aqui é a chave do elemento, e truncá-la aos 64
        // de um valor de texto fazia o campo do abandono deixar de corresponder ao
        // campo que produziu a hesitação e os erros.
        if (estado == Terminal.ABANDONADO && campo.isNotEmpty()) props["campo_abandono"] = campo.take(512)
        emitir(Tipos.TERMINAL, null, null, props)
    }

    fun marcarAtividade() {
        atividadePendente = true
        terminada = false
    }

    fun ambiente(mudanca: String, valor: String, duracao: Long? = null) {
        if (essencial()) return
        emitir(Tipos.AMBIENTE, null, duracao, mapOf("mudanca" to mudanca, "valor" to valor.take(32)))
    }

    /**
     * A aplicação deixou de estar à vista com trabalho a meio e pode nunca mais
     * voltar. É o último instante em que alguém pode marcar o abandono.
     */
    fun esconder() {
        escondido = true
        escondidoEm = agora()
        if (atividadePendente && !terminada) terminal(Terminal.ABANDONADO)
    }

    fun mostrar() {
        if (escondido) {
            // A duração de cada ausência (RF-GRA-24): o regresso é que a sabe.
            ambiente("primeiro_plano", "regresso", (agora() - escondidoEm).coerceAtLeast(0))
            escondido = false
        }
    }
}

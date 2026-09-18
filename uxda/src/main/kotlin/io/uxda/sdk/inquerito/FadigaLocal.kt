package io.uxda.sdk.inquerito

import android.content.SharedPreferences

/**
 * A primeira linha do controlo de fadiga (`RF-PER-05`), do lado do dispositivo.
 *
 * **Quem decide é o servidor**, e sem a resposta dele não se mostra nada. Isto existe
 * para não lhe perguntar o que já se sabe: uma pessoa que respondeu ontem não precisa
 * de um pedido de rede para saber que hoje não é dia.
 *
 * Guarda **dias**, e não instantes, como o livro do servidor: a pergunta é "quantas
 * vezes neste período", e um instante ao milissegundo é um rasto de quando a pessoa
 * usou a aplicação que não serve a pergunta nenhuma.
 *
 * E um relógio que andou para trás conta a favor de não perguntar: um pedido com um
 * dia no futuro fica dentro de qualquer período.
 */
internal class FadigaLocal(private val prefs: SharedPreferences) {

    companion object {
        private const val K_PEDIDOS = "fadiga.pedidos"
        private const val K_RESPONDEU = "fadiga.respondeu"
        const val DIA_MS = 24 * 60 * 60 * 1000L

        fun dia(ms: Long): Long = Math.floorDiv(ms, DIA_MS)
    }

    private fun pedidos(): List<Long> =
        prefs.getString(K_PEDIDOS, "")!!.split(',').mapNotNull { it.toLongOrNull() }

    /** O motivo por que não se pode perguntar, ou `null` quando se pode. */
    fun impedimento(agora: Long, f: Fadiga): String? {
        val hoje = dia(agora)
        val recentes = pedidos().count { hoje - it < f.periodoDias }
        if (recentes >= f.maxPedidos) return "limite_de_pedidos"
        val respondeu = prefs.getLong(K_RESPONDEU, Long.MIN_VALUE)
        if (respondeu != Long.MIN_VALUE && hoje - respondeu < f.excluirRespondeuDias) return "respondeu_recentemente"
        return null
    }

    /** O servidor contou um pedido. Os mais antigos do que um ano deixam de interessar. */
    fun registarPedido(agora: Long) {
        val hoje = dia(agora)
        val lista = (pedidos().filter { hoje - it < 400 } + hoje).takeLast(50)
        prefs.edit().putString(K_PEDIDOS, lista.joinToString(",")).apply()
    }

    fun registarResposta(agora: Long) {
        prefs.edit().putLong(K_RESPONDEU, dia(agora)).apply()
    }
}

package io.uxda.sdk.identidade

import android.content.SharedPreferences
import io.uxda.sdk.Ids

/**
 * Identidade do lado do dispositivo. RF-CAP-11, RF-CAP-12, ADR 0005 e 0011.
 *
 *   anonymous_id  quem está a usar isto, sem saber quem é. Nasce no dispositivo
 *   device_id     em que aparelho, para separar telemóvel de computador
 *   session_id    sessão técnica, e **não** unidade de análise: a unidade é a
 *                 tentativa, que se reconstrói na consulta (ADR 0002)
 */
class Identidade(private val prefs: SharedPreferences) {

    companion object {
        const val INATIVIDADE_MS = 30 * 60 * 1000L
        private const val K_ANON = "uxda.anon"
        private const val K_DISP = "uxda.dispositivo"
        private const val K_SESSAO = "uxda.sessao"
        private const val K_SESSAO_EM = "uxda.sessao.em"
        private const val K_UTIL = "uxda.utilizador"

        /**
         * Um identificador direto **não sai do dispositivo** (RNF-PRI-02). Quem
         * integra passa o que tem à mão, e o que tem à mão costuma ser o email.
         */
        fun pareceDireto(valor: String): Boolean {
            val v = valor.trim()
            if (v.contains("@") && v.contains(".")) return true
            if (Regex("""^\+?[0-9][0-9 ()\-]{6,}$""").matches(v)) return true
            if (v.contains(" ") && Regex("""^[\p{L}\s.'\-]+$""").matches(v)) return true
            return false
        }

        /** O que parecer direto é resumido aqui, e o original nunca entra num evento. */
        fun pseudonimizar(valor: String): String {
            val v = valor.trim()
            if (!pareceDireto(v)) return v.take(128)
            return "px_" + Ids.sha256(v).take(40)
        }
    }

    val anonimo: String get() = persistente(K_ANON)
    val dispositivo: String get() = persistente(K_DISP)

    var utilizador: String?
        get() = prefs.getString(K_UTIL, null)
        set(v) {
            if (v == null) prefs.edit().remove(K_UTIL).apply()
            else prefs.edit().putString(K_UTIL, v).apply()
        }

    private fun persistente(chave: String): String {
        prefs.getString(chave, null)?.let { return it }
        val novo = Ids.uuid()
        prefs.edit().putString(chave, novo).apply()
        return novo
    }

    /** A sessão técnica morre ao fim de trinta minutos sem nada acontecer. */
    fun sessao(agora: Long): String {
        val guardada = prefs.getString(K_SESSAO, null)
        val ultimo = prefs.getLong(K_SESSAO_EM, 0L)
        val id = if (guardada == null || agora - ultimo > INATIVIDADE_MS) Ids.uuid() else guardada
        prefs.edit().putString(K_SESSAO, id).putLong(K_SESSAO_EM, agora).apply()
        return id
    }
}

package io.uxea.sdk.identidade

import android.content.SharedPreferences
import io.uxea.sdk.Ids

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
        private const val K_ANON = "uxea.anon"
        private const val K_DISP = "uxea.dispositivo"
        private const val K_SESSAO = "uxea.sessao"
        private const val K_SESSAO_EM = "uxea.sessao.em"
        private const val K_UTIL = "uxea.utilizador"

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

    // Lidos uma vez e guardados: não mudam durante a vida do processo, e liam-se
    // do disco a cada evento.
    val anonimo: String by lazy { persistente(K_ANON) }
    val dispositivo: String by lazy { persistente(K_DISP) }

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

    private var sessaoEmMemoria: String? = null
    private var sessaoTocadaEm = 0L
    private var sessaoGravadaEm = 0L

    /**
     * A sessão técnica morre ao fim de trinta minutos sem nada acontecer.
     *
     * Guardada em memória e escrita no disco **no máximo de minuto a minuto**. Antes
     * lia e escrevia nas preferências a cada evento, e isso corre no fio principal:
     * a medição em gama baixa do cartão 3.4 apanhou-o. O que se perde ao gravar com
     * menos frequência é, no pior caso, um minuto de atividade a mais numa sessão
     * que o sistema tenha abatido, e isso não muda uma tentativa.
     */
    fun sessao(agora: Long): String {
        val emMemoria = sessaoEmMemoria
        val id = when {
            emMemoria != null && agora - sessaoTocadaEm <= INATIVIDADE_MS -> emMemoria
            else -> {
                val guardada = prefs.getString(K_SESSAO, null)
                val ultimo = prefs.getLong(K_SESSAO_EM, 0L)
                if (guardada == null || agora - ultimo > INATIVIDADE_MS) Ids.uuid() else guardada
            }
        }
        sessaoEmMemoria = id
        sessaoTocadaEm = agora
        if (agora - sessaoGravadaEm > 60_000L) {
            sessaoGravadaEm = agora
            prefs.edit().putString(K_SESSAO, id).putLong(K_SESSAO_EM, agora).apply()
        }
        return id
    }

    /** Grava o que estiver por gravar. Chamado quando a aplicação vai para trás. */
    fun guardarSessao() {
        val id = sessaoEmMemoria ?: return
        sessaoGravadaEm = sessaoTocadaEm
        prefs.edit().putString(K_SESSAO, id).putLong(K_SESSAO_EM, sessaoTocadaEm).apply()
    }
}

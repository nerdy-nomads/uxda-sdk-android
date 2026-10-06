package io.uxea.sdk.inquerito

import android.content.SharedPreferences
import io.uxea.sdk.Evento
import io.uxea.sdk.Tipos

/**
 * Os cinco gatilhos do `RF-PER-04`, sobre os eventos que o SDK captura.
 *
 * **Dois deles só se sabem numa sessão seguinte**, e é isso que obriga a guardar
 * estado em disco:
 *
 * - o `apos_abandono` pergunta pela tarefa que ficou a meio, e isso só se sabe
 *   quando alguém volta. A sessão em que se desistiu acaba quase sempre com o
 *   sistema a abater o processo, sem aviso nenhum, e o estado em memória morria com
 *   ele;
 * - a `primeira_utilizacao` é a primeira vez **neste dispositivo**, e não neste
 *   arranque: uma bandeira em memória perguntava a mesma coisa a cada vez que a
 *   aplicação abrisse.
 *
 * O arranque de uma sessão é o primeiro evento com um `session_id` que ainda não se
 * tinha visto. Não há outro sinal fiável: a sessão técnica sobrevive a um processo
 * abatido e reaberto dentro de trinta minutos, e é essa mesma regra que aqui se
 * herda, em vez de inventar uma segunda definição de sessão.
 *
 * Tudo corre no fio de fundo do SDK, que é por onde os eventos já passam, e nada
 * disto é chamado de outro fio: o estado não precisa de trincos.
 */
internal class Gatilhos(private val prefs: SharedPreferences) {

    /** Um gatilho que disparou, e ainda não passou pelo sorteio nem pela fadiga. */
    data class Disparo(val inquerito: Inquerito, val gatilho: String, val tentativaInicio: Long?)

    companion object {
        /** O limiar por omissão do `core/definition`: uma tentativa dura até um dia. */
        const val JANELA_MS = 24 * 60 * 60 * 1000L

        private const val K_SESSAO = "sessao"
        private const val K_PRIMEIRA = "primeira."
        private const val K_TENTATIVA = "tentativa."

        /**
         * O erro por omissão do contrato: um pedido que falhou, ou uma mensagem de
         * erro. Aplica-se só quando a configuração não trouxe critérios.
         */
        val ERRO_POR_OMISSAO = listOf(
            Criterio(listOf(Condicao("event_type", Condicoes.IGUAL, Tipos.ERRO_REDE))),
            Criterio(listOf(Condicao("message_kind", Condicoes.IGUAL, "erro"))),
        )
    }

    /**
     * A tentativa em curso de cada inquérito com `inicio`: em que sessão começou,
     * quando, e se já acabou. Três valores numa cadeia, porque as preferências não
     * guardam estruturas e um JSON por evento custava mais do que isto vale.
     */
    private data class Tentativa(val sessao: String, val inicio: Long, val terminou: Boolean) {
        fun escrever() = "$sessao|$inicio|${if (terminou) 1 else 0}"

        companion object {
            fun ler(s: String?): Tentativa? {
                val partes = s?.split('|') ?: return null
                if (partes.size != 3) return null
                val inicio = partes[1].toLongOrNull() ?: return null
                return Tentativa(partes[0], inicio, partes[2] == "1")
            }
        }
    }

    /**
     * A sessão em que se viu o último evento. É o que diz que uma sessão começou, e é
     * lida de outro fio pelo limite de um inquérito por sessão: daí o `@Volatile`.
     */
    @Volatile
    var sessaoAtual: String? = prefs.getString(K_SESSAO, null)
        private set

    private fun tentativa(chave: String) = Tentativa.ler(prefs.getString(K_TENTATIVA + chave, null))

    private fun guardar(chave: String, t: Tentativa) {
        prefs.edit().putString(K_TENTATIVA + chave, t.escrever()).apply()
    }

    /** A tentativa ainda vale para dar o `tentativa_inicio`: não acabou e tem menos de um dia. */
    private fun inicioValido(t: Tentativa?, agora: Long): Long? =
        t?.takeIf { !it.terminou && agora - it.inicio in 0 until JANELA_MS }?.inicio

    /**
     * O início da tentativa em curso deste inquérito, quando há uma. É o que o pedido
     * explícito da aplicação (`Uxea.inquerito`) leva no `tentativa_inicio`.
     */
    fun inicioEmCurso(chave: String, agora: Long): Long? = inicioValido(tentativa(chave), agora)

    /**
     * Um evento visto. Devolve o que disparou, pela ordem da configuração.
     *
     * `agora` é o instante do evento, que quem emite já sabe: ler o `occurred_at` de
     * volta de uma cadeia ISO era trabalho a mais em cada evento.
     */
    fun observar(ev: Evento, agora: Long, lista: List<Inquerito>): List<Disparo> {
        val disparos = ArrayList<Disparo>(1)

        if (ev.sessionId != sessaoAtual) {
            val anterior = sessaoAtual
            sessaoAtual = ev.sessionId
            prefs.edit().putString(K_SESSAO, ev.sessionId).apply()
            for (inq in lista) {
                when (inq.gatilho) {
                    Gatilho.AMOSTRAGEM -> disparos += Disparo(inq, Gatilho.AMOSTRAGEM, null)
                    Gatilho.APOS_ABANDONO -> {
                        // **Na sessão anterior, e só nela.** Uma tarefa começada há três
                        // sessões e nunca acabada não é o abandono de que esta pessoa
                        // se lembra, e perguntar-lhe por ela é perguntar por nada.
                        val t = tentativa(inq.chave) ?: continue
                        val inicio = inicioValido(t, agora)
                        if (anterior != null && t.sessao == anterior && inicio != null) {
                            disparos += Disparo(inq, Gatilho.APOS_ABANDONO, inicio)
                            // Marca-se como acabada: o mesmo abandono não se pergunta
                            // duas vezes.
                            guardar(inq.chave, t.copy(terminou = true))
                        }
                    }
                }
            }
        }

        for (inq in lista) {
            // **Os fins antes dos inícios.** Um evento que é ao mesmo tempo o fim de
            // uma tentativa e o começo da seguinte fecha a primeira com o instante
            // dela, e só depois abre a outra.
            val criterios = if (inq.gatilho == Gatilho.APOS_ERRO && inq.criterios.isEmpty()) {
                ERRO_POR_OMISSAO
            } else {
                inq.criterios
            }
            if (inq.gatilho != Gatilho.AMOSTRAGEM && Condicoes.algum(criterios, ev)) {
                val t = tentativa(inq.chave)
                when (inq.gatilho) {
                    Gatilho.APOS_CONCLUSAO -> {
                        disparos += Disparo(inq, inq.gatilho, inicioValido(t, agora))
                        if (t != null && !t.terminou) guardar(inq.chave, t.copy(terminou = true))
                    }
                    // O fim da tarefa chegou: não houve abandono nenhum para perguntar.
                    Gatilho.APOS_ABANDONO -> if (t != null && !t.terminou) guardar(inq.chave, t.copy(terminou = true))
                    Gatilho.APOS_ERRO -> disparos += Disparo(inq, inq.gatilho, inicioValido(t, agora))
                    Gatilho.PRIMEIRA_UTILIZACAO -> {
                        val k = K_PRIMEIRA + inq.chave
                        if (!prefs.getBoolean(k, false)) {
                            // Escrita síncrona: é uma vez na vida do dispositivo, e um
                            // processo abatido no milissegundo seguinte perguntava-a
                            // outra vez no arranque seguinte.
                            prefs.edit().putBoolean(k, true).commit()
                            disparos += Disparo(inq, inq.gatilho, inicioValido(t, agora))
                        }
                    }
                }
            }
            if (inq.inicio.isNotEmpty() && Condicoes.algum(inq.inicio, ev)) {
                val t = tentativa(inq.chave)
                // Um início repetido dentro da mesma tentativa aberta não a recomeça: a
                // pessoa voltou ao ecrã do pagamento, e a tentativa começou antes.
                if (t == null || t.terminou || inicioValido(t, agora) == null) {
                    guardar(inq.chave, Tentativa(ev.sessionId, agora, false))
                } else if (t.sessao != ev.sessionId) {
                    // A mesma tentativa, continuada noutra sessão: passa a ser desta, e é
                    // desta que o abandono se vai perguntar se ela ficar outra vez a meio.
                    guardar(inq.chave, t.copy(sessao = ev.sessionId))
                }
            }
        }
        return disparos
    }
}

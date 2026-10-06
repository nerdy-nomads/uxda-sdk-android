package io.uxea.sdk.fila

import io.uxea.sdk.Evento
import io.uxea.sdk.Seguranca
import org.json.JSONObject
import java.io.File

/**
 * A fila, em disco. RF-CAP-06, RF-CAP-07 e RNF-SDK-05.
 *
 * Em Android o problema não é o mesmo da web, e o cartão `3.2` diz porquê: **o
 * sistema mata processos**. Um utilizador que sai da aplicação a meio de uma
 * tarefa não fecha um separador, é abatido pelo sistema sem aviso, e é justamente
 * esse o evento que interessa: o abandono.
 *
 * Por isso o formato é **JSONL a acrescentar**, e não um documento reescrito:
 * gravar um evento é acrescentar uma linha e mandar sincronizar. Se o processo
 * morrer no instante seguinte, o que já foi escrito está escrito. Um ficheiro
 * único reescrito a cada evento perderia tudo o que estivesse em memória e
 * custaria o quadrado do tamanho da fila, que foi o defeito que o cartão `2.4`
 * apanhou na web.
 */
class Armazem(private val ficheiro: File, private val maxEventos: Int = 500,
              private val maxBytes: Long = 256 * 1024, private val maxIdadeMs: Long = 7 * 24 * 3600_000L) {

    private var descartados = 0

    /** Selado pela recusa do consentimento (cartão 18.1): nada volta a ser escrito. */
    @Volatile
    private var selado = false

    /** Apaga a fila do dispositivo e sela o armazém. É o que a recusa do consentimento faz. */
    @Synchronized
    fun esvaziar() {
        selado = true
        Seguranca.executar("armazem.esvaziar") { ficheiro.delete() }
    }

    init {
        Seguranca.executar("armazem.init") { ficheiro.parentFile?.mkdirs() }
    }

    @Synchronized
    fun juntar(ev: Evento) {
        Seguranca.executar("armazem.juntar") {
            if (selado) return@executar
            ficheiro.appendText(ev.paraJson().toString() + "\n")
            if (ficheiro.length() > maxBytes) aparar()
        }
    }

    @Synchronized
    fun pendentes(): List<Evento> = Seguranca.protegido("armazem.pendentes", emptyList()) {
        if (!ficheiro.exists()) return@protegido emptyList()
        val out = ArrayList<Evento>()
        ficheiro.forEachLine { linha ->
            if (linha.isNotBlank()) {
                try {
                    out.add(Evento.deJson(JSONObject(linha)))
                } catch (_: Throwable) {
                    // Linha truncada por uma morte de processo a meio da escrita.
                    // Deita-se fora **essa linha**, e não a fila inteira.
                    descartados++
                }
            }
        }
        out
    }

    /** O próximo lote, sem o tirar da fila: só sai quando a entrega confirmar. */
    @Synchronized
    fun lote(max: Int, maxBytesLote: Int): List<Evento> {
        val todos = pendentes()
        val out = ArrayList<Evento>(max)
        var bytes = 2
        for (ev in todos) {
            val b = ev.paraJson().toString().length + 1
            if (out.size >= max || (out.isNotEmpty() && bytes + b > maxBytesLote)) break
            out.add(ev)
            bytes += b
        }
        return out
    }

    /** Confirma por identificador, e não por posição: podem ter entrado eventos. */
    @Synchronized
    fun confirmar(entregues: List<Evento>) {
        if (entregues.isEmpty()) return
        Seguranca.executar("armazem.confirmar") {
            val ids = entregues.map { it.eventId }.toHashSet()
            val restantes = pendentes().filter { it.eventId !in ids }
            reescrever(restantes)
        }
    }

    /**
     * Corta o mais antigo e o que passou da idade. O limite de idade não é
     * arrumação: um evento de há duas semanas já não descreve nada que alguém vá
     * corrigir, e ocupa a fila de quem ficou sem rede ontem.
     */
    @Synchronized
    fun aparar(agora: Long = System.currentTimeMillis()) {
        Seguranca.executar("armazem.aparar") {
            var linhas = pendentes()
            val antes = linhas.size
            linhas = linhas.filter { idadeMs(it, agora) <= maxIdadeMs }
            if (linhas.size > maxEventos) linhas = linhas.takeLast(maxEventos)
            var total = linhas.sumOf { it.paraJson().toString().length + 1 }
            while (linhas.size > 1 && total > maxBytes) {
                total -= linhas.first().paraJson().toString().length + 1
                linhas = linhas.drop(1)
            }
            descartados += antes - linhas.size
            reescrever(linhas)
        }
    }

    private fun idadeMs(ev: Evento, agora: Long): Long = Seguranca.protegido("armazem.idade", 0L) {
        val t = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.parse(ev.occurredAt)?.time ?: return@protegido 0L
        agora - t
    }

    private fun reescrever(linhas: List<Evento>) {
        if (selado) return
        val temporario = File(ficheiro.parentFile, ficheiro.name + ".novo")
        temporario.bufferedWriter().use { w ->
            for (ev in linhas) {
                w.write(ev.paraJson().toString())
                w.newLine()
            }
        }
        // Trocar por renomeação: se o processo morrer a meio, ou fica o ficheiro
        // antigo inteiro ou o novo inteiro, e nunca meio ficheiro.
        if (!temporario.renameTo(ficheiro)) {
            ficheiro.delete()
            temporario.renameTo(ficheiro)
        }
    }

    @Synchronized
    fun quantos(): Int = pendentes().size

    @Synchronized
    fun perdidos(): Int = descartados

    @Synchronized
    fun limpar() {
        Seguranca.executar("armazem.limpar") { ficheiro.delete() }
    }
}

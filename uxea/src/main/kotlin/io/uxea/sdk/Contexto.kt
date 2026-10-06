package io.uxea.sdk

import android.app.Application
import android.content.res.Configuration
import android.os.Build
import java.util.TimeZone

/**
 * O contexto do dispositivo, que é a base da segmentação. RF-MET-12, cartão 7.6.
 *
 * É o espelho exato do `captura/contexto.ts` do SDK web: os mesmos quatro campos,
 * com os mesmos nomes e os mesmos valores possíveis. A paridade entre os dois
 * canais depende disto, porque um `os_name` escrito `android` de um lado e
 * `Android` do outro faz o mesmo sistema aparecer como dois segmentos.
 *
 * Quatro sinais, e o que ficou de fora custou mais a decidir do que o que ficou:
 *
 * - **`Build.VERSION.RELEASE` cortado na parte maior.** `14` segmenta; `14.0.1`
 *   com o número da compilação é uma impressão digital, e num parque pequeno chega
 *   para distinguir pessoas.
 * - **`Build.MODEL` não sai.** Um modelo raro numa amostra de mil é um
 *   identificador. A classe responde à pergunta que a segmentação faz.
 * - **A geografia é o fuso, e nunca o IP nem coordenadas** (ADR 0022). Não se pede
 *   `ACCESS_FINE_LOCATION`, não se lê a rede móvel, e a regra `fuso_e_nao_lugar`
 *   do esquema recusa qualquer coisa que se pareça com um par de coordenadas.
 */
internal object Contexto {

    /** Lê-se uma vez: nada disto muda dentro de um processo. */
    @Volatile
    private var classe: String = "desconhecido"

    fun iniciar(app: Application) = Seguranca.executar("contexto.iniciar") {
        classe = classeDe(app)
    }

    /**
     * Telemóvel ou tablet pela largura mínima em dp, que é o critério do próprio
     * Android para escolher recursos. **600 dp**, e não o tamanho do ecrã em
     * polegadas: é a mesma linha que o sistema usa para decidir o esquema, e por
     * isso é a que corresponde ao que a pessoa vê.
     */
    private fun classeDe(app: Application): String {
        val cfg: Configuration = app.resources.configuration
        val larguraDp = cfg.smallestScreenWidthDp
        // `0` acontece em ambientes de ensaio sem recursos carregados. Nesse caso
        // dizer "desconhecido" é melhor do que adivinhar telemóvel: um valor
        // inventado num campo de segmentação é um segmento que não existe.
        return when {
            larguraDp <= 0 -> "desconhecido"
            larguraDp >= 600 -> "tablet"
            else -> "telemovel"
        }
    }

    /** Só a parte maior da versão. O resto é impressão digital. */
    internal fun maior(v: String?): String? {
        if (v.isNullOrBlank()) return null
        val digitos = v.trimStart().takeWhile { it.isDigit() }
        return digitos.ifEmpty { null }
    }

    fun osName(): String = "Android"

    fun osVersion(): String? = maior(Build.VERSION.RELEASE)

    fun deviceClass(): String = classe

    /**
     * O fuso IANA do dispositivo. Devolve `null` quando o sistema dá um
     * identificador que não é um fuso, em vez de o mandar na mesma: o validador
     * recusaria o evento inteiro, e perder-se-ia a medição por causa da geografia.
     */
    fun timeZone(): String? {
        val id = TimeZone.getDefault()?.id ?: return null
        return if (pareceFuso(id)) id else null
    }

    /**
     * A mesma regra do `pareceFuso` do esquema, escrita aqui porque o dispositivo
     * decide antes de enviar. Aceita `Area/Local` e `Area/Sub/Local`, mais os nomes
     * sem barra que existem de facto.
     */
    internal fun pareceFuso(s: String): Boolean {
        if (s == "UTC" || s == "GMT" || s == "Z") return true
        val barras = s.count { it == '/' }
        if (s.length > 64 || barras < 1 || barras > 2) return false
        if (s.first() !in 'A'..'Z') return false
        return s.all { it.isLetterOrDigit() || it == '/' || it == '_' || it == '-' || it == '+' }
    }
}

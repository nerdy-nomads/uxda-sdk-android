package io.uxea.sdk

import io.uxea.sdk.identidade.Mascara

/**
 * O que a aplicação anfitriã nos dá, mascarado por omissão. Cartão 18.1,
 * `RNF-PRI-04`, ADR 0047. As mesmas três regras do SDK web, pela mesma ordem:
 *
 *  1. Uma chave que o esquema não conhece não sai, e conta-se.
 *  2. Um valor de texto sai mascarado, com as regras de uma mensagem sem chave.
 *  3. Só a lista de permissões da instituição levanta a máscara, e nunca o chão:
 *     números, identificadores e correio saem sempre mascarados.
 */
internal object Privacidade {

    /** O comprimento máximo de um valor de propriedade, o mesmo do validador da ingestão. */
    private const val MAX = 64

    fun textoDoCliente(valor: Any?, chave: String, expostas: List<String>): String {
        val t = (valor?.toString() ?: "").replace(Regex("\\s+"), " ").trim()
        return (if (chave in expostas) Mascara.mascarar(t) else Mascara.mascararMensagem(t)).take(MAX)
    }

    class Filtradas(val propriedades: Map<String, Any>, val descartadas: List<String>)

    fun propriedadesDoCliente(bruto: Map<String, Any?>, expostas: List<String>): Filtradas {
        val props = LinkedHashMap<String, Any>()
        val fora = ArrayList<String>()
        for ((k, v) in bruto) {
            if (k !in EsquemaGerado.PERMITIDAS) {
                fora.add(k.take(40))
                continue
            }
            when {
                v is Boolean -> props[k] = v
                v is Int || v is Long -> props[k] = v
                v is Number && v.toDouble().isFinite() -> props[k] = v
                v is String && k in EsquemaGerado.IDENTIFICADOR && v.contains("v1|f=") -> props[k] = v.take(512)
                v is String -> textoDoCliente(v, k, expostas).takeIf { it.isNotEmpty() }?.let { props[k] = it }
                else -> fora.add(k.take(40))
            }
        }
        return Filtradas(props, fora)
    }

    /* --------------------------------------------- o consentimento (18.1) */

    /** As preferências onde fica a recusa, e só a recusa. */
    const val PREFS_CONSENTIMENTO = "uxea.consentimento"

    /** Os ficheiros de preferências que o SDK escreve, e que a recusa apaga. */
    val PREFS_DO_SDK = listOf("uxea", "uxea.inqueritos")

    /**
     * Como os eventos vão viajar até ao servidor (cartão 18.6, `RNF-PRI-09`, ADR 0050).
     *
     * `https` é cifrado. HTTP só se aceita para a própria máquina (`localhost`, e o
     * anfitrião visto do emulador, `10.0.2.2`), que é o ensaio local. Qualquer outro
     * endereço em claro é **recusado**, e o SDK não arranca.
     */
    fun transporteDe(servidor: String): String {
        val u = try { java.net.URI(servidor) } catch (_: Throwable) { return "recusado" }
        return when (u.scheme?.lowercase()) {
            "https" -> if (u.host.isNullOrEmpty()) "recusado" else "cifrado"
            "http" -> {
                val h = (u.host ?: "").trim('[', ']')
                if (h == "localhost" || h.endsWith(".localhost") || h == "127.0.0.1" || h == "::1" || h == "10.0.2.2") "local" else "recusado"
            }
            else -> "recusado"
        }
    }
}

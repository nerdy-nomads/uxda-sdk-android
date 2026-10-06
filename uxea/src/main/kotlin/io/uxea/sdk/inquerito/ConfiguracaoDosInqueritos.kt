package io.uxea.sdk.inquerito

import org.json.JSONArray
import org.json.JSONObject

/**
 * O campo `inqueritos` da configuração remota. Cartões 14.1 e 14.2, `RF-PER-01` a
 * `RF-PER-05`, e o contrato em `docs/contrato-das-respostas.md`.
 *
 * **A leitura é desconfiada de propósito, e a direção do erro é sempre "não
 * perguntar".** Um inquérito mal escrito na consola não pode fazer o componente
 * aparecer a toda a gente, nem com a pergunta vazia, nem com uma escala sem
 * opções. Por isso cada entrada que não se consegue ler por inteiro sai da lista, e
 * uma condição que não se percebe **tira o critério todo**, e não só a condição: um
 * critério com uma condição a menos corresponde a mais eventos, e o que se quer é o
 * contrário.
 *
 * Os valores por omissão são os do contrato: amostragem de 0,1 (e nunca "toda a
 * gente sempre"), um pedido por pessoa em trinta dias, e noventa dias de descanso
 * depois de alguém responder.
 */
data class ConfigInqueritos(
    val tema: Tema = Tema(),
    val fadiga: Fadiga = Fadiga(),
    val associarRespostas: Boolean = false,
    val lista: List<Inquerito> = emptyList(),
) {
    val ativo: Boolean get() = lista.isNotEmpty()

    fun porChave(chave: String): Inquerito? = lista.firstOrNull { it.chave == chave }

    companion object {
        val VAZIA = ConfigInqueritos()

        /** Mais do que isto numa aplicação é um questionário, e não um inquérito. */
        private const val MAX_INQUERITOS = 50

        fun deJson(o: Any?): ConfigInqueritos {
            if (o !is JSONObject) return VAZIA
            val lista = ArrayList<Inquerito>()
            (o.opt("lista") as? JSONArray)?.let { a ->
                for (i in 0 until minOf(a.length(), MAX_INQUERITOS)) {
                    Inquerito.deJson(a.opt(i))?.let(lista::add)
                }
            }
            return ConfigInqueritos(
                tema = Tema.deJson(o.opt("tema")),
                fadiga = Fadiga.deJson(o.opt("fadiga")),
                // Só o booleano `true` liga, como o rastreio individual: associar uma
                // resposta a uma pessoa não pode começar por uma cadeia "true" mal
                // escrita.
                associarRespostas = o.opt("associar_respostas") == true,
                lista = lista,
            )
        }

        /* ---------------------------------------------- leitores sem surpresas */

        /** Texto a sério, e não um número convertido: o `optString` faz isso. */
        internal fun texto(o: JSONObject, chave: String, max: Int): String? =
            (o.opt(chave) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

        internal fun numero(o: JSONObject, chave: String): Double? =
            (o.opt(chave) as? Number)?.toDouble()?.takeUnless { it.isNaN() || it.isInfinite() }
    }
}

/**
 * O aspeto do componente, que a instituição escolhe na consola (`RF-PER-02`).
 *
 * As cores chegam em hexadecimal de CSS (`#rgb`, `#rrggbb`, `#rrggbbaa`), e são
 * lidas aqui à mão: o `Color.parseColor` do Android lê o alfa **à frente** e
 * transformava um `#1f4fd1cc` numa cor que ninguém escolheu.
 */
data class Tema(
    val corPrimaria: Int = 0xFF1F4FD1.toInt(),
    val corFundo: Int = 0xFFFFFFFF.toInt(),
    val corTexto: Int = 0xFF1B1F24.toInt(),
    val fonte: String = "system-ui, sans-serif",
    val cantosPx: Int = 12,
    val idioma: String = "pt",
) {
    val ingles: Boolean get() = idioma == "en"

    companion object {
        fun deJson(o: Any?): Tema {
            val base = Tema()
            if (o !is JSONObject) return base
            val cantos = ConfigInqueritos.numero(o, "cantos_px")
            return Tema(
                corPrimaria = cor(o.opt("cor_primaria")) ?: base.corPrimaria,
                corFundo = cor(o.opt("cor_fundo")) ?: base.corFundo,
                corTexto = cor(o.opt("cor_texto")) ?: base.corTexto,
                fonte = ConfigInqueritos.texto(o, "fonte", 200) ?: base.fonte,
                // Acima de 32 o cartão deixa de ser um cartão e passa a ser uma
                // cápsula que corta o texto nos cantos.
                cantosPx = cantos?.toInt()?.coerceIn(0, 32) ?: base.cantosPx,
                idioma = (o.opt("idioma") as? String)?.takeIf { it == "pt" || it == "en" } ?: base.idioma,
            )
        }

        /** `#rgb`, `#rrggbb` ou `#rrggbbaa`, com o alfa no fim, como no CSS. */
        fun cor(v: Any?): Int? {
            val s = (v as? String)?.trim()?.removePrefix("#") ?: return null
            if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            val cheio = when (s.length) {
                3 -> s.map { "$it$it" }.joinToString("") + "ff"
                6 -> s + "ff"
                8 -> s
                else -> return null
            }
            val rgba = cheio.toLong(16)
            val a = (rgba and 0xff)
            val rgb = rgba ushr 8
            return ((a shl 24) or rgb).toInt()
        }
    }
}

/** O controlo de fadiga (`RF-PER-05`). Por pessoa e por projeto, e não por inquérito. */
data class Fadiga(
    val maxPedidos: Int = 1,
    val periodoDias: Int = 30,
    val excluirRespondeuDias: Int = 90,
) {
    companion object {
        fun deJson(o: Any?): Fadiga {
            val base = Fadiga()
            if (o !is JSONObject) return base
            return Fadiga(
                maxPedidos = ConfigInqueritos.numero(o, "max_pedidos")?.toInt()?.takeIf { it >= 1 } ?: base.maxPedidos,
                periodoDias = ConfigInqueritos.numero(o, "periodo_dias")?.toInt()?.takeIf { it >= 1 } ?: base.periodoDias,
                // Zero é legítimo, e quer dizer que responder não dá descanso nenhum.
                excluirRespondeuDias = ConfigInqueritos.numero(o, "excluir_respondeu_dias")?.toInt()
                    ?.takeIf { it >= 0 } ?: base.excluirRespondeuDias,
            )
        }
    }
}

/** Uma opção de um inquérito de `escolha`, nas duas línguas do `RF-PAI-14`. */
data class Opcao(val chave: String, val pt: String, val en: String) {
    fun texto(ingles: Boolean): String = if (ingles) en else pt
}

/** `campo`, `operador` e `valor`, com a mesma lista fechada do `core/definition`. */
data class Condicao(val campo: String, val operador: String, val valor: String)

/** Condições que valem **todas ao mesmo tempo**. Os critérios de uma lista valem em ou. */
data class Criterio(val condicoes: List<Condicao>)

/** Um inquérito, tal como o dispositivo o recebe. */
data class Inquerito(
    val chave: String,
    val versao: Int,
    val formato: String,
    val perguntaPt: String,
    val perguntaEn: String,
    val opcoes: List<Opcao>,
    val multipla: Boolean,
    val comentario: Boolean,
    val gatilho: String,
    val criterios: List<Criterio>,
    val inicio: List<Criterio>,
    val amostragem: Double,
    val atrasoMs: Long,
    val tarefa: String,
    val passo: String,
    val funcionalidade: String,
) {
    fun pergunta(ingles: Boolean): String = if (ingles) perguntaEn else perguntaPt

    /** A escala do formato, ou `null` quando o formato não tem nota. */
    val escala: IntRange?
        get() = when (formato) {
            Formatos.ESFORCO -> 1..7
            Formatos.SATISFACAO -> 1..5
            Formatos.RECOMENDACAO -> 0..10
            else -> null
        }

    companion object {
        /** A omissão do contrato, e nunca "toda a gente sempre". */
        const val AMOSTRAGEM_POR_OMISSAO = 0.1

        /** Dois minutos. Uma pergunta que aparece mais tarde já não é sobre o que se fez. */
        private const val ATRASO_MAXIMO_MS = 120_000L

        fun deJson(v: Any?): Inquerito? {
            val o = v as? JSONObject ?: return null
            val chave = ConfigInqueritos.texto(o, "chave", 64) ?: return null
            val formato = (o.opt("formato") as? String)?.takeIf { it in Formatos.TODOS } ?: return null
            val gatilho = (o.opt("gatilho") as? String)?.takeIf { it in Gatilho.TODOS } ?: return null

            val pergunta = o.opt("pergunta") as? JSONObject
            val pt = pergunta?.let { ConfigInqueritos.texto(it, "pt", 300) }
            val en = pergunta?.let { ConfigInqueritos.texto(it, "en", 300) }
            // Uma pergunta sem texto em nenhuma língua não se mostra; com uma só,
            // mostra-se na que existe em vez de mostrar um cartão em branco.
            if (pt == null && en == null) return null

            val opcoes = ArrayList<Opcao>()
            (o.opt("opcoes") as? JSONArray)?.let { a ->
                for (i in 0 until minOf(a.length(), 20)) {
                    val op = a.opt(i) as? JSONObject ?: continue
                    val k = ConfigInqueritos.texto(op, "chave", 64) ?: continue
                    val opPt = ConfigInqueritos.texto(op, "pt", 120)
                    val opEn = ConfigInqueritos.texto(op, "en", 120)
                    if (opPt == null && opEn == null) continue
                    if (opcoes.any { it.chave == k }) continue
                    opcoes += Opcao(k, opPt ?: opEn!!, opEn ?: opPt!!)
                }
            }
            // Uma escolha sem opções não se consegue responder, e mostrá-la era
            // gastar o pedido do período a esta pessoa em troca de nada.
            if (formato == Formatos.ESCOLHA && opcoes.isEmpty()) return null

            val criterios = criteriosDe(o.opt("criterios"))
            val inicio = criteriosDe(o.opt("inicio"))
            // Um gatilho que depende de critérios e ficou sem nenhum legível não
            // dispara, em vez de disparar para tudo. O erro tem critérios por omissão
            // no contrato, e é o `Gatilhos` que os aplica.
            if ((gatilho == Gatilho.APOS_CONCLUSAO || gatilho == Gatilho.PRIMEIRA_UTILIZACAO ||
                    gatilho == Gatilho.APOS_ABANDONO) && criterios.isEmpty()
            ) return null
            if (gatilho == Gatilho.APOS_ABANDONO && inicio.isEmpty()) return null

            val amostragem = ConfigInqueritos.numero(o, "amostragem")
                ?.takeIf { it in 0.0..1.0 } ?: AMOSTRAGEM_POR_OMISSAO
            val atraso = ConfigInqueritos.numero(o, "atraso_ms")?.toLong()?.coerceIn(0L, ATRASO_MAXIMO_MS) ?: 0L
            val contexto = o.opt("contexto") as? JSONObject

            return Inquerito(
                chave = chave,
                versao = ConfigInqueritos.numero(o, "versao")?.toInt()?.coerceAtLeast(0) ?: 0,
                formato = formato,
                perguntaPt = pt ?: en!!,
                perguntaEn = en ?: pt!!,
                opcoes = if (formato == Formatos.ESCOLHA) opcoes else emptyList(),
                multipla = formato == Formatos.ESCOLHA && o.opt("multipla") == true,
                // No `livre` o texto é a resposta, e não um extra: a bandeira não se
                // aplica.
                comentario = formato != Formatos.LIVRE && o.opt("comentario") == true,
                gatilho = gatilho,
                criterios = criterios,
                inicio = inicio,
                amostragem = amostragem,
                atrasoMs = atraso,
                tarefa = contexto?.let { ConfigInqueritos.texto(it, "tarefa", 64) } ?: "",
                passo = contexto?.let { ConfigInqueritos.texto(it, "passo", 64) } ?: "",
                funcionalidade = contexto?.let { ConfigInqueritos.texto(it, "funcionalidade", 64) } ?: "",
            )
        }

        private fun criteriosDe(v: Any?): List<Criterio> {
            val a = v as? JSONArray ?: return emptyList()
            val saida = ArrayList<Criterio>()
            // Seis e oito são os limites do `core/definition`: acima deles a definição
            // nem se guarda, e o dispositivo não tem de ser mais permissivo.
            for (i in 0 until minOf(a.length(), 6)) {
                val c = a.opt(i) as? JSONObject ?: continue
                val lista = c.opt("condicoes") as? JSONArray ?: continue
                if (lista.length() == 0 || lista.length() > 8) continue
                val condicoes = ArrayList<Condicao>()
                var legivel = true
                for (k in 0 until lista.length()) {
                    val cond = condicaoDe(lista.opt(k))
                    if (cond == null) {
                        legivel = false
                        break
                    }
                    condicoes += cond
                }
                if (legivel) saida += Criterio(condicoes)
            }
            return saida
        }

        private fun condicaoDe(v: Any?): Condicao? {
            val o = v as? JSONObject ?: return null
            val campo = (o.opt("campo") as? String) ?: return null
            val operador = (o.opt("operador") as? String) ?: return null
            if (!Condicoes.campoValido(campo) || operador !in Condicoes.OPERADORES) return null
            val valor = (o.opt("valor") as? String) ?: ""
            if (valor.length > 256) return null
            // A mesma regra do `Validar` do Go: só `existe` e `nao_existe` vivem sem valor.
            if (operador != Condicoes.EXISTE && operador != Condicoes.NAO_EXISTE && valor.isBlank()) return null
            return Condicao(campo, operador, valor)
        }
    }
}

/** Os cinco formatos do `RF-PER-03`. */
object Formatos {
    const val ESFORCO = "esforco"
    const val SATISFACAO = "satisfacao"
    const val RECOMENDACAO = "recomendacao"
    const val ESCOLHA = "escolha"
    const val LIVRE = "livre"
    val TODOS = setOf(ESFORCO, SATISFACAO, RECOMENDACAO, ESCOLHA, LIVRE)
}

/** Os cinco gatilhos do `RF-PER-04`. */
object Gatilho {
    const val APOS_CONCLUSAO = "apos_conclusao"
    const val APOS_ABANDONO = "apos_abandono"
    const val APOS_ERRO = "apos_erro"
    const val PRIMEIRA_UTILIZACAO = "primeira_utilizacao"
    const val AMOSTRAGEM = "amostragem"
    /**
     * Não é um gatilho da configuração: é a aplicação a pedir pela API (`Uxea.inquerito`).
     * Chama-se `manual`, **o mesmo nome do SDK web e do contrato**: com dois nomes para a
     * mesma coisa, a análise contava os pedidos do Android e os do browser como gatilhos
     * diferentes.
     */
    const val MANUAL = "manual"
    val TODOS = setOf(APOS_CONCLUSAO, APOS_ABANDONO, APOS_ERRO, PRIMEIRA_UTILIZACAO, AMOSTRAGEM)
}

package io.uxea.sdk

import java.security.MessageDigest
import java.util.UUID

/**
 * Identificadores e as duas contas que **têm de dar o mesmo** que dão no SDK web
 * e no servidor: o resumo do rótulo e a decisão de amostragem.
 *
 * Se divergirem, duas coisas partem-se ao mesmo tempo: o mesmo botão passa a ser
 * dois elementos diferentes conforme o canal, e o telemóvel envia o que o
 * servidor deita fora.
 */
object Ids {

    fun uuid(): String = UUID.randomUUID().toString()

    /**
     * FNV-1a de 32 bits sobre as **unidades de código UTF-16**, que é o que o
     * `charCodeAt` do JavaScript percorre. Sobre bytes UTF-8 daria outro valor
     * para qualquer texto com acentos, e "Iniciar sessão" tem um.
     */
    fun resumo(texto: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in texto) {
            h = h xor c.code
            h *= 0x01000193
        }
        return String.format("%08x", h)
    }

    /**
     * FNV-1a sobre **bytes UTF-8**, que é o que o servidor faz em Go. É a conta da
     * amostragem, e por isso é esta e não a de cima.
     */
    fun hash32(texto: String): Long {
        var h = 0x811c9dc5L
        for (b in texto.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xff)
            h = (h * 0x01000193L) and 0xffffffffL
        }
        return h
    }

    /** A mesma decisão que o `ratelimit.PassaNaAmostra` do servidor toma. */
    fun naAmostra(id: String, fracao: Double): Boolean {
        if (fracao >= 1.0) return true
        if (fracao <= 0.0) return false
        return (hash32(id) % 10000).toDouble() / 10000.0 < fracao
    }

    /** Resumo forte, para pseudonimizar um identificador direto no dispositivo. */
    fun sha256(texto: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}

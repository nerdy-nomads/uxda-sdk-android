package io.uxea.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A hora, em RFC 3339 com milissegundos e em UTC, que é o que o esquema canónico
 * exige e o que o motor analítico aceita.
 *
 * O formatador é **um por fio**, e não um por chamada. O `SimpleDateFormat` não é
 * seguro entre fios, e criar um custa mais do que parece: a medição no emulador de
 * gama baixa do cartão 3.4 mostrou-o, porque isto corre no fio principal, dentro do
 * caminho de cada toque.
 */
object Relogio {
    private val formatador = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    fun iso(ms: Long): String = formatador.get()!!.format(Date(ms))
}

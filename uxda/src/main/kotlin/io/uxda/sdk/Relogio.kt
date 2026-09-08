package io.uxda.sdk

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A hora, em RFC 3339 com milissegundos e em UTC, que é o que o esquema canónico
 * exige e o que o motor analítico aceita.
 *
 * O formatador é criado por chamada de propósito: o `SimpleDateFormat` não é
 * seguro entre fios, e este é chamado do fio de captura e do fio de envio.
 */
object Relogio {
    fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))
}

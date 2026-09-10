package io.uxda.sdk

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.TimeZone

/**
 * O contexto do dispositivo segmenta, e não identifica. Cartão 7.6.
 *
 * É o espelho do `contexto.test.ts` do SDK web, e é de propósito: a paridade entre
 * os dois canais não é intenção, é medida, e um `os_name` escrito de duas maneiras
 * faz o mesmo sistema aparecer como dois segmentos no painel.
 */
@RunWith(RobolectricTestRunner::class)
class ContextoTest {

    @Test
    fun `a versao sai so na parte maior`() {
        assertEquals("14", Contexto.maior("14"))
        assertEquals("14", Contexto.maior("14.0.1"))
        assertEquals("9", Contexto.maior("9"))
        assertNull(Contexto.maior(null))
        assertNull(Contexto.maior(""))
        // Uma pré-lançamento como "VanillaIceCream" não tem número, e o que se
        // manda é nada, e não o nome de código: o nome de código é mais raro do
        // que a versão, e por isso identifica mais.
        assertNull(Contexto.maior("VanillaIceCream"))
    }

    @Test
    fun `o modelo do dispositivo nunca sai, so a classe`() {
        val app = RuntimeEnvironment.getApplication() as Application
        Contexto.iniciar(app)
        val json = evento().paraJson().toString()
        assertTrue(json, json.contains("\"os_name\":\"Android\""))
        assertTrue(json, json.contains("\"device_class\""))
        // O modelo e a compilação não aparecem em campo nenhum.
        for (proibido in listOf("Build.MODEL", android.os.Build.MODEL, android.os.Build.FINGERPRINT)) {
            if (proibido.isNullOrBlank() || proibido == "unknown") continue
            assertFalse("o modelo escapou: $json", json.contains(proibido))
        }
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun `um ecra largo e tablet`() {
        Contexto.iniciar(RuntimeEnvironment.getApplication() as Application)
        assertEquals("tablet", Contexto.deviceClass())
    }

    @Test
    @Config(qualifiers = "sw360dp")
    fun `um ecra estreito e telemovel`() {
        Contexto.iniciar(RuntimeEnvironment.getApplication() as Application)
        assertEquals("telemovel", Contexto.deviceClass())
    }

    @Test
    fun `a geografia e o fuso, e coordenadas nao passam`() {
        assertTrue(Contexto.pareceFuso("Europe/Lisbon"))
        assertTrue(Contexto.pareceFuso("America/Argentina/Buenos_Aires"))
        assertTrue(Contexto.pareceFuso("UTC"))
        assertFalse(Contexto.pareceFuso("38.7223,-9.1393"))
        assertFalse(Contexto.pareceFuso("lisboa"))
        assertFalse(Contexto.pareceFuso("-9.1393"))

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Lisbon"))
        assertEquals("Europe/Lisbon", Contexto.timeZone())
    }

    @Test
    fun `um fuso que o sistema nao sabe nomear nao rebenta o evento`() {
        // Um `GMT+01:00` não é um fuso IANA e o validador recusaria o evento
        // inteiro. Perder a medição por causa da geografia seria trocar o essencial
        // pelo acessório: o que se perde é o segmento, e não o evento.
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+01:00"))
        assertNull(Contexto.timeZone())
        val json = evento().paraJson().toString()
        assertFalse(json, json.contains("time_zone"))
        assertTrue(json, json.contains("\"event_type\":\"ecra\""))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun evento() = Evento(
        eventId = "3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        anonymousId = "a1", deviceId = "d1", sessionId = "s1",
        eventType = "ecra", screenKey = "inicio",
        occurredAt = "2027-03-14T10:00:00Z",
        appVersion = "1.0.0", captureLevel = "padrao",
    )
}

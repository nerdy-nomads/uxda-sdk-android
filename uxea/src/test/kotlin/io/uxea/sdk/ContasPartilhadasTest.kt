package io.uxea.sdk

import io.uxea.sdk.identidade.Identidade
import io.uxea.sdk.identidade.Mascara
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * As contas que **têm de dar o mesmo** aqui, no SDK web e no servidor.
 *
 * Os valores esperados não foram calculados aqui: saíram do `sdk-js` e do `hash/fnv`
 * do Go, e estão escritos à mão de propósito. Se alguém mudar uma destas funções, o
 * ensaio falha em vez de o sistema passar a ter duas verdades.
 */
class ContasPartilhadasTest {

    @Test
    fun `o mascaramento e regra a regra o do SDK web`() {
        assertEquals("pagar {numero} kz", Mascara.preparar("Pagar 12.400,00 Kz"))
        assertEquals("pagar {numero} kz", Mascara.preparar("Pagar 300 Kz"))
        assertEquals("desconto de {numero}%", Mascara.preparar("Desconto de 15%"))
        assertEquals("fatura de {data}", Mascara.preparar("Fatura de 2026-09-07"))
        assertEquals("sessao as {hora}", Mascara.preparar("Sessão às 14:30"))
        assertEquals("enviar para {email}", Mascara.preparar("Enviar para ana@exemplo.ao"))
        assertEquals("conta {id}", Mascara.preparar("Conta AO06000600000100037131174"))
    }

    @Test
    fun `duas variantes da mesma mensagem dao o mesmo resumo`() {
        assertEquals(
            Mascara.resumoDe("O saldo de 12.400 Kz é insuficiente"),
            Mascara.resumoDe("O saldo de 300 Kz é insuficiente"),
        )
    }

    @Test
    fun `o resumo do rotulo e o mesmo que o SDK web calcula`() {
        // Vindos do `resumo()` do sdk-js, corridos sobre o texto já preparado:
        //   node --experimental-strip-types -e 'import("./src/identity/mask.ts")...'
        assertEquals("a2fb1510", Ids.resumo("pagar"))
        assertEquals("45577b04", Ids.resumo("iniciar sessao"))
        assertEquals("811c9dc5", Ids.resumo(""))
        assertEquals("95322e3e", Ids.resumo("continuar"))
        // E o caminho completo, com mascaramento pelo meio: é o que entra no sinal
        // do rótulo, e o que tem de bater certo entre os dois canais.
        assertEquals("f4bac9d2", io.uxea.sdk.identidade.Mascara.resumoDe("A pagar com o cartão 4111111111111111"))
    }

    @Test
    fun `a amostragem e a mesma conta do servidor`() {
        // Do `hash/fnv` do Go, com as mesmas entradas.
        assertEquals(4281602306L, Ids.hash32("u-1"))
        assertEquals(2332815293L, Ids.hash32("a1b2c3"))
        assertEquals(3480239522L, Ids.hash32("00000000-0000-4000-8000-000000000001"))
        assertEquals(399627198L, Ids.hash32("utilizador-ção"))
        assertTrue(Ids.naAmostra("u-1", 0.3))
        assertFalse(Ids.naAmostra("u-1", 0.2))
        assertTrue(Ids.naAmostra("u-1", 1.0))
        assertFalse(Ids.naAmostra("u-1", 0.0))
    }

    @Test
    fun `um identificador direto e resumido antes de sair`() {
        for (direto in listOf("ana@exemplo.ao", "+244 923 000 111", "Ana Maria Silva")) {
            assertTrue("$direto devia ser reconhecido como direto", Identidade.pareceDireto(direto))
            val pseudo = Identidade.pseudonimizar(direto)
            assertTrue(pseudo.startsWith("px_"))
            assertFalse(pseudo.contains("@"))
            assertFalse(pseudo.contains("Ana"))
            assertFalse(pseudo.contains("923"))
        }
    }

    @Test
    fun `um pseudonimo que ja vem opaco passa tal e qual`() {
        assertEquals("cliente-8f31c0", Identidade.pseudonimizar("cliente-8f31c0"))
        assertFalse(Identidade.pareceDireto("cliente-8f31c0"))
    }

    @Test
    fun `o mesmo identificador da sempre o mesmo pseudonimo`() {
        assertEquals(
            Identidade.pseudonimizar("ana@exemplo.ao"),
            Identidade.pseudonimizar("ana@exemplo.ao"),
        )
    }
}

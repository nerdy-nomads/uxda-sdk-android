package io.uxda.sdk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A prova de que este SDK **nunca fotografa o ecrã**. Cartão 9.2, RF-IND-04,
 * RNF-PRI-01.
 *
 * # Porque é que isto é um ensaio e não um comentário
 *
 * O mapa de calor do cartão 9.2 desenha-se sobre um esquema reconstruído a partir
 * das caixas dos elementos observados, e a razão escrita em todo o lado é que
 * gravar o ecrã implicaria capturar conteúdo. Essa frase aparece em vários
 * ficheiros do produto, e até este ensaio nenhum deles a verificava.
 *
 * **Em Android o risco é maior do que na web**, e é por isso que a lista é mais
 * longa: `View.draw(Canvas)` desenha uma árvore inteira num bitmap com uma linha,
 * `PixelCopy` copia a janela, `MediaProjection` grava o ecrã, e `drawToBitmap` da
 * androidx faz o primeiro sem se ver que o faz. Qualquer uma cabe numa linha
 * escrita com boa intenção, e o SDK passava a levar pixéis de um ecrã com dados
 * de clientes sem nenhum ensaio de comportamento mudar.
 *
 * # E é uma proibição e não uma heurística
 *
 * Percorre o código publicado e falha se encontrar qualquer uma delas. Não há caso
 * legítimo: a identidade de um elemento são cinco sinais (cartão 3.3), a posição é
 * uma percentagem do visor (cartão 9.1), e nenhum dos dois precisa de uma imagem.
 *
 * É a irmã do `FugaTest`, que prova que nenhum conteúdo de campo sai daqui. Aquele
 * olha para o que sairia; este olha para o que o código sabe fazer.
 */
class SemEcraTest {

    /**
     * As formas de tirar uma fotografia a um ecrã em Android.
     *
     * Cada entrada diz **porque é que está aqui**, senão a lista envelhece e
     * ninguém sabe se pode tirar uma linha dela.
     */
    private val proibido = listOf(
        "PixelCopy" to "copia os pixéis de uma janela",
        "MediaProjection" to "grava o ecrã",
        "drawToBitmap" to "desenha uma vista inteira num bitmap",
        "getDrawingCache" to "devolve o bitmap desenhado da vista",
        "setDrawingCacheEnabled" to "liga o bitmap desenhado da vista",
        "Bitmap.createBitmap" to "cria a imagem onde uma vista se desenharia",
        "Canvas(" to "é o destino onde uma vista se desenha",
        "compress(" to "comprime um bitmap para ficheiro",
        "screenshot" to "diz o que é pelo nome",
        "takeSurfaceCapture" to "captura a superfície de uma janela",
    )

    /** O código que é publicado: `src/main`. Os ensaios não vão no pacote. */
    private fun fontes(): List<File> {
        val raiz = File("src/main/kotlin")
        assertTrue("não encontrei o código em ${raiz.absolutePath}", raiz.isDirectory)
        return raiz.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `nenhum ficheiro do SDK sabe fotografar um ecra`() {
        val ficheiros = fontes()
        assertTrue(
            "só ${ficheiros.size} ficheiros percorridos: o ensaio não está a ver o código",
            ficheiros.size > 10,
        )
        val faltas = mutableListOf<String>()
        for (f in ficheiros) {
            val fonte = f.readText()
            for ((padrao, porque) in proibido) {
                // O comentário também conta, e é de propósito: a frase "não usamos
                // PixelCopy" é indistinguível de uma chamada para quem lê isto
                // depressa, e escrever a proibição sem a palavra proibida custa uma
                // reescrita e vale a pena.
                if (fonte.contains(padrao)) faltas += "${f.path}: $padrao ($porque)"
            }
        }
        assertEquals(
            "o SDK ganhou um caminho de captura de ecrã:\n  " + faltas.joinToString("\n  "),
            emptyList<String>(), faltas,
        )
    }

    @Test
    fun `a posicao de um toque e uma percentagem, e nao um pixel`() {
        // A outra metade da mesma promessa: o que sai do dispositivo sobre *onde* a
        // pessoa tocou é uma percentagem do visor, e por isso não reconstitui um
        // ecrã nem um modelo de aparelho. O ensaio olha para a captura de toques,
        // que é o único sítio onde isto se decide.
        val fonte = File("src/main/kotlin/io/uxda/sdk/captura/Toques.kt").readText()
        assertTrue("a captura de toques deixou de converter para percentagem",
            fonte.contains("porCento"))
        assertTrue("o alvo deixou de sair em percentagem da caixa",
            fonte.contains("alvo_caixa"))
    }
}

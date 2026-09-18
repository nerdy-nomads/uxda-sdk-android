package io.uxda.sdk.captura

import android.view.View

/**
 * Uma vista que é **do próprio SDK**, e que a captura nunca lê. Cartão 14.1.
 *
 * O componente de inquérito é a primeira interface que o SDK desenha dentro da
 * aplicação de quem nos instala, e isso abre um defeito que não existia: a captura
 * percorre a árvore de vistas inteira, e o cartão está lá dentro. Sem esta marca, o
 * toque numa nota da escala saía como um `toque` da aplicação, o comentário escrito
 * saía como um `campo` com contagens de caracteres, e a submissão do inquérito
 * contava como uma submissão do formulário que estava por baixo. As métricas do
 * cliente passavam a medir a nossa pergunta sobre elas.
 *
 * **Uma interface, e não uma etiqueta.** O `setTag(int, ...)` exige um
 * identificador de recurso da aplicação e rebenta com qualquer outro número, e uma
 * etiqueta de texto é o sítio onde a aplicação põe as dela (`uxda:mensagem=`). Uma
 * classe não colide com nada e não se perde ao reciclar a vista.
 */
interface VistaDoSdk

internal object DoSdk {

    /**
     * A vista, ou alguma acima dela, é do SDK. A subida é limitada, como todas as da
     * captura: uma árvore patológica não pode prender o fio principal aqui.
     */
    fun ehDoSdk(v: View?): Boolean {
        var atual: View? = v
        var passos = 0
        while (atual != null && passos < 32) {
            if (atual is VistaDoSdk) return true
            atual = atual.parent as? View
            passos++
        }
        return false
    }
}

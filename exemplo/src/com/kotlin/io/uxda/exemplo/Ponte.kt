package io.uxda.exemplo

import io.uxda.sdk.Uxda
import io.uxda.sdk.UxdaOkHttp

/**
 * A variante **com** SDK. É a única diferença entre as duas variantes da aplicação
 * de ensaio, e é o que faz a medição do cartão 3.4 comparar a mesma coisa.
 *
 * Repare-se no que não está aqui: nenhuma chamada a arrancar o SDK. Ele arranca
 * sozinho, pela entrada no manifesto.
 */
object Ponte {
    fun diagnostico(): String = Uxda.diagnosticoEmTexto()
    fun identificar(id: String) = Uxda.identificar(id)
    fun track(nome: String) = Uxda.track(nome)
    fun ecra(nome: String) = Uxda.ecra(nome)
    fun pedidoComecou() = Uxda.pedidoComecou()
    fun pedidoAcabou(ms: Long) = Uxda.pedidoAcabou(ms)
    fun mensagem(chave: String, tipo: String) = Uxda.mensagem(chave, tipo)
    fun erroTecnico(chave: String, operacao: String) = Uxda.erroTecnico(chave, operacao)
    fun inquerito(chave: String) = Uxda.inquerito(chave)
    fun intercetor() = UxdaOkHttp.intercetor()
}

package io.uxea.exemplo

import io.uxea.sdk.Uxea
import io.uxea.sdk.UxeaOkHttp

/**
 * A variante **com** SDK. É a única diferença entre as duas variantes da aplicação
 * de ensaio, e é o que faz a medição do cartão 3.4 comparar a mesma coisa.
 *
 * Repare-se no que não está aqui: nenhuma chamada a arrancar o SDK. Ele arranca
 * sozinho, pela entrada no manifesto.
 */
object Ponte {
    fun diagnostico(): String = Uxea.diagnosticoEmTexto()
    fun identificar(id: String) = Uxea.identificar(id)
    fun track(nome: String) = Uxea.track(nome)
    fun ecra(nome: String) = Uxea.ecra(nome)
    fun pedidoComecou() = Uxea.pedidoComecou()
    fun pedidoAcabou(ms: Long) = Uxea.pedidoAcabou(ms)
    fun mensagem(chave: String, tipo: String) = Uxea.mensagem(chave, tipo)
    fun erroTecnico(chave: String, operacao: String) = Uxea.erroTecnico(chave, operacao)
    fun inquerito(chave: String) = Uxea.inquerito(chave)
    fun intercetor() = UxeaOkHttp.intercetor()
}

package io.uxda.sdk.captura

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import io.uxda.sdk.Seguranca
import io.uxda.sdk.Tipos

/**
 * Ecrãs feitos de fragmentos. RF-CAP-04, primeiro tipo.
 *
 * Metade das aplicações Android navega assim: uma atividade só, e o ecrã muda por
 * dentro. Sem isto, uma aplicação de uma atividade e vinte fragmentos aparecia
 * como **um ecrã só** durante a sessão inteira, que é o mesmo defeito que as rotas
 * em `#` deram no SDK web e que se apanhou lá pela mesma razão: correr a sério.
 *
 * O `androidx.fragment` entra como dependência **de compilação**: numa aplicação
 * que não o use, nada disto é sequer carregado, e o `existe()` confirma-o antes de
 * se tocar em qualquer classe.
 *
 * A primeira tentativa foi com um `Proxy` dinâmico para não depender de nada. Não
 * funciona, e degradava em silêncio: `FragmentLifecycleCallbacks` é uma **classe
 * abstrata**, e um `Proxy` só sabe implementar interfaces. O ensaio no emulador
 * mostrou-o: a atividade `/apoio` aparecia, e os fragmentos lá dentro não.
 */
internal object Fragmentos {

    private val registados = java.util.WeakHashMap<Any, Boolean>()

    /** Só existe se a aplicação anfitriã trouxer o `androidx.fragment`. */
    fun existe(): Boolean = Seguranca.protegido("fragmentos.existe", false) {
        Class.forName("androidx.fragment.app.FragmentActivity")
        true
    }

    fun ligar(a: Activity, definirEcra: (String) -> Unit, emitir: (String) -> Unit) {
        Seguranca.executar("fragmentos.ligar") {
            if (!existe()) return@executar
            val atividade = a as? FragmentActivity ?: return@executar
            val gestor = atividade.supportFragmentManager
            if (registados.containsKey(gestor)) return@executar
            registados[gestor] = true
            gestor.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        Seguranca.executar("fragmentos.retomado") {
                            definirEcra(nomeDe(f))
                            emitir(Tipos.ECRA)
                        }
                    }
                },
                // `true` para apanhar também os fragmentos dentro de fragmentos,
                // que é como um ecrã com abas costuma estar feito.
                true,
            )
        }
    }

    private fun nomeDe(f: Fragment): String {
        val nome = f.javaClass.simpleName.removeSuffix("Fragment").ifEmpty { f.javaClass.simpleName }
        return "/" + nome.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()
    }
}

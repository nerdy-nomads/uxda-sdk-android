package io.uxea.exemplo

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * Um ecrã feito de **fragmentos**, que é como metade das aplicações Android navega
 * dentro da mesma atividade.
 *
 * Existe para provar a primeira caixa do cartão 3.1: sem isto, uma aplicação de
 * uma atividade e vinte fragmentos aparecia como **um ecrã só** durante a sessão
 * inteira, que é o mesmo defeito que as rotas em `#` deram na web.
 *
 * Não há aqui uma única chamada ao SDK.
 */
class ApoioActivity : AppCompatActivity() {

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Um identificador qualquer, estável: o gestor de fragmentos precisa de
            // um contentor com identificador para lá pôr as vistas.
            id = 0x00ABCDEF
            setPadding(40, 40, 40, 40)
        }
        val trocar = Button(this).apply {
            text = "Falar com alguém"
            setOnClickListener {
                supportFragmentManager.beginTransaction()
                    .replace(raiz.id, ContactoFragment())
                    .addToBackStack(null)
                    .commit()
            }
        }
        raiz.addView(trocar)
        setContentView(raiz)
        supportFragmentManager.beginTransaction().add(raiz.id, PerguntasFragment()).commit()
    }

    class PerguntasFragment : Fragment() {
        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: android.view.ViewGroup?,
            estado: Bundle?,
        ) = TextView(requireContext()).apply { text = "Perguntas frequentes" }
    }

    class ContactoFragment : Fragment() {
        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: android.view.ViewGroup?,
            estado: Bundle?,
        ) = TextView(requireContext()).apply { text = "Contacto do apoio" }
    }
}

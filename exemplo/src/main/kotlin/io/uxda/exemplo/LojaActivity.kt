package io.uxda.exemplo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * A loja de ensaio, em vistas clássicas: a **mesma tarefa** da loja de ensaio da
 * web, para as duas sequências poderem ser comparadas passo a passo (cartão 3.1).
 *
 * Não há uma única chamada ao SDK aqui dentro, tirando o botão de entrar, que
 * existe para mostrar a ligação da identidade, e o painel de diagnóstico.
 */
class LojaActivity : androidx.appcompat.app.AppCompatActivity() {

    private val mao = Handler(Looper.getMainLooper())
    private lateinit var diagnostico: TextView

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        setContentView(R.layout.loja)
        diagnostico = findViewById(R.id.diagnostico)

        // O identificador do botão de pagar mudou entre as duas versões do ecrã,
        // como muda numa aplicação a sério. A atividade procura os dois.
        val pagar = findViewById<Button>(resources.getIdentifier("pagar", "id", packageName))
            ?: findViewById<Button>(resources.getIdentifier("botao_pagamento", "id", packageName))
        pagar?.setOnClickListener {
            // Validação da aplicação, com o mecanismo da plataforma. O SDK vê o
            // `setError` como vê o `invalid` na web.
            val nome = findViewById<EditText>(R.id.nome)
            val cartao = findViewById<EditText>(R.id.cartao)
            if (nome.text.isNullOrBlank()) nome.error = "Falta o nome"
            if ((cartao.text?.length ?: 0) < 12) cartao.error = "Número incompleto"
        }

        findViewById<Button>(R.id.entrar).setOnClickListener {
            Ponte.identificar("ana.silva@exemplo.ao")
        }

        // As mensagens do cartão 5.1. A aplicação **só as mostra**: quem as vê,
        // classifica e mascara é o SDK, sem uma linha de instrumentação.
        val avisos = findViewById<android.widget.LinearLayout>(R.id.avisos)
        fun mostrar(texto: String, chave: String?, id: Int = R.id.erro_geral) {
            val v = TextView(this)
            // O nome do recurso é o que a captura lê para saber que aquilo é uma
            // mensagem **e de que tipo**, tal como na web lê a classe. `erro_geral`
            // dá um erro; um `aviso_ligacao` daria um aviso.
            v.id = id
            v.text = texto
            if (chave != null) v.tag = "uxda:mensagem=$chave"
            v.setPadding(0, 12, 0, 12)
            avisos.addView(v)
            mao.postDelayed({ avisos.removeView(v) }, 6000)
        }
        findViewById<Button>(R.id.msg_chave).setOnClickListener {
            mostrar("Saldo insuficiente para esta operação", "saldo_insuficiente")
        }
        findViewById<Button>(R.id.msg_texto).setOnClickListener {
            // Montante, documento, data e um nome, tudo lá dentro. É o risco
            // crítico do documento, e o que sai é
            // `O saldo de {numero} Kz do documento {id} ...`.
            mostrar("O saldo de 12.400,50 Kz do documento 005123456LA041 nao chega para Ana Maria da Silva em 2027-03-14", null, R.id.aviso_geral)
        }
        findViewById<Button>(R.id.msg_tecnico).setOnClickListener {
            // O que corre mal **sem chegar ao ecrã** (RF-MSG-06). Nada aparece à
            // pessoa, e é isso que o torna a causa de abandono mais difícil de
            // explicar.
            Ponte.erroTecnico("resposta_ilegivel", "pagamento")
        }

        // O inquérito pedido pela aplicação (cartão 14.1). Com `?.`, porque a segunda
        // versão do esquema não tem estes botões.
        findViewById<Button?>(R.id.inquerito_escolha)?.setOnClickListener { Ponte.inquerito("porque_desistiu") }
        findViewById<Button?>(R.id.inquerito_recomendacao)?.setOnClickListener { Ponte.inquerito("recomendacao") }

        findViewById<Button>(R.id.falhar).setOnClickListener {
            Thread {
                // Porta fechada de propósito: um 404 é a aplicação a dizer que não,
                // e isso é comportamento normal. O que interessa medir é o pedido
                // que **não chega**.
                try {
                    OkHttpClient.Builder().addInterceptor(Ponte.intercetor()).build()
                        .newCall(Request.Builder().url("http://10.0.2.2:9/api/pedidos/9182").build())
                        .execute().close()
                } catch (_: IOException) {
                }
            }.start()
        }

        // O botão lento fica ocupado dois segundos e não diz nada a ninguém. É
        // onde se vê o toque em carregamento e o toque repetido, que são o
        // sistema a não responder à vista de quem está do outro lado.
        findViewById<Button>(R.id.lento).setOnClickListener {
            // Dois segundos ocupado, e sem depender de rede nenhuma: um ensaio que
            // só funciona com internet é um ensaio que não corre quando faz falta.
            Ponte.pedidoComecou()
            mao.postDelayed({ Ponte.pedidoAcabou(2000) }, 2000)
        }

        findViewById<Button>(R.id.compose).setOnClickListener {
            startActivity(Intent(this, ComposeActivity::class.java))
        }

        findViewById<Button>(R.id.apoio).setOnClickListener {
            startActivity(Intent(this, ApoioActivity::class.java))
        }

        mao.post(object : Runnable {
            override fun run() {
                val d = Ponte.diagnostico()
                diagnostico.text = d
                // Também no registo do sistema: nos ensaios em dispositivo não há
                // consola nem depurador, e é por aqui que os números saem sem
                // ninguém os transcrever de uma captura de ecrã.
                android.util.Log.i("UxdaExemplo", d.replace("\n", " "))
                mao.postDelayed(this, 2000)
            }
        })
    }
}

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

package io.uxda.exemplo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A **segunda versão** do ecrã em Compose, com o esquema alterado como acontece
 * numa versão nova:
 *
 *  - uma `Column` nova a envolver o formulário, que muda o caminho na árvore;
 *  - o `testTag` do botão de pagar mudou de `botao-pagar` para `pagar-encomenda`;
 *  - o preço mudou, como mudou nas vistas clássicas.
 *
 * É o mesmo ensaio do cartão 3.3, do outro lado da árvore de interface.
 */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        Ponte.ecra("/compose/pagamento")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Pagar a encomenda", style = MaterialTheme.typography.headlineSmall)
                        var nome by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = nome,
                            onValueChange = { nome = it },
                            label = { Text("Nome no cartão") },
                            modifier = Modifier.testTag("campo-nome"),
                        )
                        Button(
                            onClick = { Ponte.track("compose_pagar") },
                            modifier = Modifier.testTag("pagar-encomenda"),
                        ) { Text("Pagar 15.900 Kz") }
                        Button(
                            onClick = { Ponte.track("compose_ajuda") },
                            modifier = Modifier.semantics { contentDescription = "Pedir ajuda" },
                        ) { Text("Ajuda") }
                      }
                    }
                }
            }
        }
    }
}

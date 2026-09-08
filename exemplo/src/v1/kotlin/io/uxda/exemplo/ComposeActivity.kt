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
 * O mesmo ecrã, em Compose. Existe para o cartão 3.3: em Compose não há vistas, há
 * uma só `AndroidComposeView` com o ecrã inteiro lá dentro, e sem ler a árvore
 * semântica **todos os toques deste ecrã dariam o mesmo elemento**.
 *
 * O `testTag` está aqui porque quem escreve testes de interface já o põe. O ecrã
 * também funciona sem ele: aí a identidade sai do papel, do texto e da posição.
 */
class ComposeActivity : ComponentActivity() {
    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        Ponte.ecra("/compose/pagamento")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
                            modifier = Modifier.testTag("botao-pagar"),
                        ) { Text("Pagar 12.400 Kz") }
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

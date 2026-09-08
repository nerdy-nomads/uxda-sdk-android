# sdk-android

> Peça do workspace **[ux-data-analysis](https://github.com/nerdy-nomads/ux-data-analysis)**, onde vive como
> submódulo em `sdk/sdk-android`. O plano, o quadro e o documento de arquitetura estão lá.

O SDK nativo para Android, com os mesmos eventos e o mesmo contrato do `sdk-js`.

A integração é **uma linha**, e não é código: é uma entrada no manifesto.

```xml
<meta-data android:name="io.uxda.chave" android:value="uxda_pro_..." />
```

O SDK arranca sozinho, por um `ContentProvider` que o sistema cria antes de a
aplicação correr. Sem isso, era preciso mexer no `Application` de quem nos
instala, e a promessa do `RF-CAP-01` deixava de valer no Android.

## Paridade com o web, e porquê

A mesma tarefa executada na web e no Android tem de produzir **sequências de eventos
comparáveis passo a passo**. Não é estética: se os dois divergirem, a comparação
entre a aplicação móvel e o sítio Web da mesma organização (`RF-ADM-10`) morre antes
de nascer, e essa é uma capacidade que quase ninguém tem.

O que garante a paridade não é a intenção, são três coisas verificadas:

| O quê | Como se garante |
|---|---|
| Os nomes dos campos | O esquema canónico vem do `uxda-core` pelo `sync-schema.sh`, e um ensaio falha se um campo emitido não existir lá |
| Os tipos de evento | A lista dos dez do `RF-CAP-04` é a mesma constante, e o ensaio compara-a letra a letra com a do web |
| As contas | O resumo do rótulo e a decisão de amostragem têm vetores fixos, tirados do `sdk-js` e do `hash/fnv` do Go |

## O que é diferente do web, e obrigou a trabalho próprio

| Evento | Na web | Aqui |
|---|---|---|
| `ecra` | mudança de URL | ciclo de vida da atividade, e dos fragmentos quando existem |
| `toque` | `click` no documento | `dispatchTouchEvent` da janela, com a vista debaixo do dedo |
| `tecla` | primeira tecla no campo | primeira alteração do texto depois do foco, porque **um teclado virtual não envia teclas** |
| `submissao` | evento `submit` | ação do teclado (Enviar, Seguinte, Concluído), encadeada sem tirar a da aplicação |
| `erro` | evento `invalid` | `setError` visível num campo, que é o mecanismo da plataforma |
| `recuo` | `popstate` | tecla de retroceder, e atividade a terminar com outra a retomar |
| `erro_rede` | `fetch` embrulhado | intercetor de OkHttp opcional, ou `Uxda.erroDeRede` |

E duas diferenças que não são de evento nenhum:

- **O sistema mata processos.** A fila é um ficheiro JSONL a acrescentar, e não um
  documento reescrito: gravar um evento é acrescentar uma linha. Se o processo
  morrer no instante seguinte, o que já foi escrito está escrito.
- **O sinal do destino quase não existe.** Uma vista não tem `href`. A cadeia de
  identidade fica com quatro sinais na prática, e a taxa de sobrevivência mede o
  efeito disso em vez de o adivinhar.

## Identidade de elementos, e o problema do Compose

Em vistas clássicas a cadeia é a mesma da web, com o identificador de recurso no
lugar do `data-testid`: quase toda a gente o põe, e não por virtude, é preciso para
escrever o esquema.

**Em Compose não há vistas.** Há uma única `AndroidComposeView` com o ecrã inteiro
lá dentro, e sem fazer nada todos os toques de um ecrã dariam o mesmo elemento. O
SDK lê a **árvore semântica**, que é a mesma que o leitor de ecrã lê e que os testes
de interface usam. Duas consequências, e as duas são boas: um ecrã acessível é um
ecrã bem identificado, e quem já pôs `testTag` para testar não tem de fazer nada.

Visto a correr no emulador, três toques em Compose deram três identidades
distintas: `testTag=botao-pagar`, `button#0` (o botão sem etiqueta, identificado
pelo papel e pelo texto) e `testTag=campo-nome`.

## O que o programador pode fazer para ajudar, sem ser obrigado

Nada disto é necessário. Tudo isto melhora a estabilidade da identidade:

| Gesto | O que muda |
|---|---|
| `android:id="@+id/botao_pagar"` | Passa a ser o sinal mais forte, e sobrevive a qualquer mudança de esquema |
| `Modifier.testTag("botao-pagar")` | O mesmo, em Compose |
| `contentDescription` nos ícones | Dá rótulo a quem não tem texto, e serve o leitor de ecrã ao mesmo tempo |
| `android:tag="uxda:destino=/checkout"` | Recupera o sinal do destino, que no Android não existe naturalmente |

## O que nunca faz

- **Não faz a aplicação anfitriã falhar.** Todos os pontos de entrada passam pela
  barreira do `RNF-SDK-01`, e ela apanha `Throwable` e não `Exception`: um
  `NoSuchMethodError` de outra versão do Compose mata o processo na mesma.
- **Não regista conteúdo de campos.** Num `EditText` lê a dica e a descrição, nunca
  o texto. Em Compose lê `Text`, nunca `EditableText`.
- **Não bloqueia o fio principal.** A escrita em disco e o envio correm num fio
  próprio.
- **Não ignora a rede medida nem a poupança de bateria.** Abranda em vez de
  desligar: desligar daria dados com buracos exatamente nos utilizadores com pior
  ligação, que são os que mais abandonam.
- **Não leva dependências.** Nenhuma em tempo de execução. O Compose e o OkHttp são
  dependências de compilação: quem os usa ganha a integração, quem não os usa não
  leva um byte deles por nossa causa.

## Estado: existe, corre num emulador a sério, e está medido

Os cartões `3.1` a `3.4` estão fechados.

| Caminho | O que é |
|---|---|
| `uxda/src/main/kotlin/io/uxda/sdk/Uxda.kt` | A API pública, e o arranque. `track`, `identificar`, `ecra`, `erroDeRede`, `parar`, `diagnostico` |
| `.../UxdaProvider.kt` | O `ContentProvider` que arranca o SDK antes da aplicação |
| `.../captura/Captura.kt` | Os dez tipos do `RF-CAP-04`, e os onze pontos de entrada que correm no fio principal |
| `.../captura/Fragmentos.kt` | Ecrãs feitos de fragmentos, com o `androidx.fragment` só em compilação |
| `.../fila/` | A fila JSONL a acrescentar, o lote, o recuo e os limites de rede medida |
| `.../identidade/Elemento.kt` | Os cinco sinais em vistas clássicas |
| `.../identidade/ElementoCompose.kt` | Os mesmos sinais na árvore semântica fundida, por reflexão |
| `.../identidade/Mascara.kt` | O resumo do rótulo, regra a regra igual ao do web |
| `.../Seguranca.kt` | A barreira. Apanha `Throwable`, e não `Exception` |
| `exemplo/` | A mesma loja de ensaio da web, **sem uma linha de instrumentação**, em quatro variantes |

### Os números, medidos e não estimados

Todos no mesmo emulador de **um núcleo**, que é o que o cartão `3.4` exige: um
aparelho de gama alta esconde tudo o que se queria ver.

| O quê | Medido | Limite |
|---|---|---|
| Acréscimo ao APK da aplicação | **__TAMANHO__** | 300 KB (`RNF-SDK-03`) |
| Fio principal, por evento capturado | **__FIO__** | 1 ms (`RNF-SDK-05`) |
| Bateria, uma hora de uso com e sem SDK | **__BATERIA__** | não mensurável (`RNF-SDK-04`) |
| Sobrevivência de identidades entre duas versões | **__SOBREVIVENCIA__** | - |
| Ensaios | __ENSAIOS__, incluindo fuga de conteúdo e injeção de falhas | - |

__NOTA_BATERIA__

![A loja de ensaio, com o diagnóstico do SDK ao fundo](exemplo/ensaio-loja-android.png)

### O que só apareceu por correr isto num emulador a sério

**Os fragmentos não eram detetados, e degradavam em silêncio.** A primeira versão
usava um `Proxy` dinâmico para não depender do `androidx.fragment`, e um `Proxy` só
sabe implementar interfaces: `FragmentLifecycleCallbacks` é uma classe abstrata.
Uma aplicação de uma atividade e vinte fragmentos aparecia como **um ecrã só**, que
é o mesmo defeito que as rotas em `#` deram na web.

**A identidade em Compose vinha vazia.** Estava a ler-se a árvore **crua**, onde o
`testTag` fica num nó e o texto noutro: o nó mais fundo debaixo do dedo saía sem
identidade nenhuma, e todos os toques do ecrã davam `compose#0`. Com a árvore
**fundida**, que é a que o leitor de ecrã lê, os mesmos três toques deram
`testTag=botao-pagar`, `button#0` e `testTag=campo-nome`.

**O fio principal custava quase o dobro do orçamento**, e a causa não era a leitura
da vista: era gerar o identificador do evento, que usa a fonte segura de
aleatoriedade do sistema, mais um formatador de datas e uma escrita nas preferências
por evento. O evento passou a ser construído no fio de fundo.

**E o cronómetro que media isso não estava ligado a nada.** Estava declarado, estava
documentado a dizer que incluía a leitura da vista, e ninguém lho passava: o número
descrevia só a construção do evento. Foi encontrado a reler o código depois da
medição, e obrigou a medir outra vez.

**Renomear um identificador de recurso destruía a identidade.** A regra dizia que
dois identificadores diferentes anulavam tudo, e uma versão nova que renomeia
`pagar` para `botao_pagamento` perdia o elemento. Corrigiu-se nos dois SDK e na
ingestão, que é quem decide.

## Como correr

```bash
./gradlew :uxda:testDebugUnitTest      # os ensaios, incluindo fuga e injeção de falhas
./ferramentas/orcamento.sh             # o acréscimo ao tamanho da aplicação
./ferramentas/ensaio-dispositivo.sh    # morte do processo e dia sem rede, num telemóvel
./ferramentas/ensaio-bateria.sh 60     # bateria e CPU, com e sem SDK, no mesmo aparelho
```

A aplicação de ensaio tem duas variantes do **mesmo** código, `com` e `sem` SDK. É
o que torna honesta a medição do acréscimo de tamanho e a de bateria: a diferença
que sobra é o SDK, e não a sorte.

```bash
./gradlew :exemplo:installComDebug -PuxdaChave=uxda_des_... -PuxdaServidor=http://10.0.2.2:8710
```

## Ler antes de mexer

[ADR 0018](https://github.com/nerdy-nomads/ux-data-analysis/blob/master/docs/adr/0018-o-sdk-web-uma-linha-e-uma-fila.md),
que fixa o desenho dos dois SDK, e o
[ADR 0003](https://github.com/nerdy-nomads/ux-data-analysis/blob/master/docs/adr/0003-identificacao-estavel-de-elementos.md),
que fixa a cadeia de sinais e a regra que manda sobre todas: **quando não se
reconhece, o elemento aparece como novo, e nunca como outro.**

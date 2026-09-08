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

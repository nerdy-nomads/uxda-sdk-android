# sdk-android

> Peça do workspace **[ux-data-analysis](https://github.com/nerdy-nomads/ux-data-analysis)**, onde vive como
> submódulo em `sdk/sdk-android`. O plano, o quadro e o documento de arquitetura estão lá.

O SDK nativo para Android, com os mesmos eventos e o mesmo contrato do `sdk-js`.

**Estado.** Ainda não tem código. O que se segue é a especificação, e os cartões que
a constroem estão no fim.

## Paridade com o web, e porquê

A mesma tarefa executada na web e no Android tem de produzir **sequências de eventos
comparáveis passo a passo**. Não é estética: se os dois divergirem, a comparação
entre a aplicação móvel e o sítio Web da mesma organização (`RF-ADM-10`) morre antes
de nascer, e essa é uma capacidade que quase ninguém tem.

Verifica-se com a **mesma aplicação de ensaio** nas duas plataformas.

## O que é diferente do web, e obriga a trabalho próprio

| Problema | Porque é diferente aqui |
|---|---|
| **Identidade dos elementos** | A árvore de vistas não tem os apoios do DOM. As vistas clássicas e o Compose comportam-se de forma diferente, e **os dois têm de funcionar** |
| **Ciclo de vida** | O sistema mata processos. A fila é persistente em disco e sobrevive a isso |
| **Rede** | Alterna entre móvel e sem fios, e o utilizador pode ficar dias sem abrir a aplicação |
| **Bateria** | O impacto tem de ser **não mensurável em utilização normal** (`RNF-SDK-04`), e mede-se onde dói |

## O que nunca faz

- **Não faz a aplicação anfitriã falhar**, e prova-se com injeção sistemática de
  falhas em todos os pontos de entrada.
- **Não regista conteúdo de campos.**
- **Não bloqueia o fio principal da interface.**
- **Não ignora a rede medida nem a poupança de bateria** no envio.
- **Não descarta eventos** quando o processo morre a meio de uma tentativa.

## A medição faz-se em gama baixa

Um dispositivo de gama alta esconde tudo. O documento nomeia o risco: *captura
granular degrada bateria ou desempenho da aplicação integrada*, e a mitigação escrita
é **validação em dispositivos de gama baixa antes de qualquer lançamento**.

Os números de bateria, tamanho e tempo no fio principal escrevem-se no cartão `3.4`.

## Como se verifica

**Arrancando um emulador Android a sério**, ou ligando um dispositivo. Ver a variante
web a funcionar não é verificar o Android, e `tsc` limpo diz que compila, não que
aparece.

O emulador não vê o `localhost` da máquina: o anfitrião é `10.0.2.2`.

## Stack

**Kotlin**, vistas clássicas e Compose. Distribuído por Maven.

## Os cartões que o constroem

| Cartão | O que acrescenta |
|---|---|
| `3.1` | Fundação e paridade de eventos |
| `3.2` | Fila, offline e envio em lote |
| `3.3` | Identificação estável de elementos, nas duas árvores de interface |
| `3.4` | Bateria, tamanho e nunca falhar a anfitriã |
| `4.1` a `4.5` | Captura granular e agregação no dispositivo |
| `5.1` a `5.4` | Mensagens de sistema e mascaramento |
| `14.1` | Componente de avaliação embutido |
| `18.1`, `18.2` | Mascaramento por omissão e testes de fuga |

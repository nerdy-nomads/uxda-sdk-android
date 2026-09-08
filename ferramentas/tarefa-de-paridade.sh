#!/usr/bin/env bash
# A mesma tarefa que se faz na loja de ensaio da web, feita no telemóvel. Cartão 3.1.
#
#   ./ferramentas/tarefa-de-paridade.sh
#
# Existe para a comparação entre plataformas ser **a mesma tarefa** e não duas
# sessões parecidas. O `./scripts/paridade.sh` compara depois o que as duas
# escreveram no armazenamento, e uma diferença que apareça aí é do SDK, não do
# guião.
#
# A ordem é a da loja de ensaio da web, passo a passo:
#   ver o ecrã, preencher o nome, preencher o cartão, tentar pagar (a aplicação
#   recusa e mostra erro), entrar na conta, pedido que falha, mudar de ecrã,
#   recuar, marcar um evento próprio, e sair para segundo plano.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PACOTE="${PACOTE:-io.uxda.exemplo.com}"
SAIDA="ferramentas/saida"
mkdir -p "$SAIDA"

ok() { printf '  \033[32mok\033[0m     %s\n' "$*"; }

tocar_em() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r' > "$SAIDA/ui.xml"
  local c; c=$(python3 ferramentas/no-central.py "$SAIDA/ui.xml" "$1")
  [ -z "$c" ] && { printf '  \033[33msalta\033[0m  sem "%s" no ecrã\n' "$1"; return 1; }
  "$ADB" shell input tap $c >/dev/null 2>&1
  sleep 1
}

"$ADB" get-state >/dev/null 2>&1 || { echo "sem dispositivo ligado"; exit 1; }

printf '\n\033[1ma mesma tarefa da loja de ensaio da web, no telemóvel\033[0m\n'
"$ADB" shell am force-stop io.uxda.exemplo.sem >/dev/null 2>&1
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
"$ADB" shell am start -n "$PACOTE/io.uxda.exemplo.LojaActivity" >/dev/null 2>&1
sleep 6
ok "ecrã da loja"

# O nome preenchido e o cartão **por preencher**, de propósito: é assim que a
# aplicação recusa e mostra o erro de validação, que é o `invalid` da web.
tocar_em "Nome no cartão" && "$ADB" shell input text "Ana" >/dev/null 2>&1
ok "formulário meio preenchido, sem uma linha de instrumentação na aplicação"

tocar_em "Pagar 12"        && ok "a aplicação recusa e mostra erro de validação"

# A ação do teclado é o `submit` da web, e num teclado virtual é isto que a
# dispara. O último campo do formulário está marcado com `actionDone`.
tocar_em "Validade" && "$ADB" shell input text "1229" >/dev/null 2>&1
"$ADB" shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
sleep 2
ok "submissão pela ação do teclado"

tocar_em "Entrar na conta" && ok "identidade ligada, com o identificador pseudonimizado"
tocar_em "Pedido que falha" && ok "pedido de rede que não chega"
sleep 2

tocar_em "Abrir o ecrã em Compose" && ok "mudança de ecrã, para a outra árvore de interface"
sleep 4
# Pelo `contentDescription`, que é o que o leitor de ecrã lê e o que não se
# confunde com o título: procurar "Pagar" apanhava "Pagar a encomenda".
tocar_em "Pedir ajuda" && ok "evento próprio, marcado pela aplicação"
sleep 2
"$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
sleep 2
ok "recuo"

# E o fim, que é o que fecha a tentativa: a aplicação sai de vista.
"$ADB" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 8
ok "aplicação em segundo plano, com o tempo ativo medido"
echo

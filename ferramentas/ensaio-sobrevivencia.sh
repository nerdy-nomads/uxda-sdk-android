#!/usr/bin/env bash
# A taxa de sobrevivência das identidades entre duas versões. Cartão 3.3, RF-CAP-03.
#
#   ./ferramentas/ensaio-sobrevivencia.sh <chave-de-ingestao>
#
# O que se mede: de todos os elementos que a **primeira** versão da aplicação deu a
# conhecer, quantos é que a ingestão reconhece outra vez na **segunda**, depois de o
# esquema mudar como muda numa versão nova (um invólucro novo, um identificador
# renomeado, campos por outra ordem, textos diferentes).
#
# Duas decisões que fazem a diferença entre um número e um número honesto:
#
#  - **um projeto só para esta medição.** A taxa compara a primeira versão vista com
#    a última, e num projeto já usado para outra coisa os elementos descobertos
#    depois contam como não sobreviventes. Cria-se a chave com
#    `go run ./tools/semear -projeto <uuid> -so-chave`, no uxea-ingest;
#  - **os toques vão pela árvore de interface e não por coordenadas.** A segunda
#    versão mudou os campos de sítio de propósito, e um toque em coordenadas fixas
#    tocaria noutra coisa, medindo o guião em vez do SDK.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
CHAVE="${1:-${UXEA_CHAVE:-}}"
SERVIDOR="${UXEA_SERVIDOR:-http://10.0.2.2:8710}"
INGESTAO="${UXEA_INGESTAO:-http://localhost:8710}"
PACOTE=io.uxea.exemplo.com
SAIDA="ferramentas/saida"
mkdir -p "$SAIDA"

passo()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()     { printf '  \033[32mok\033[0m     %s\n' "$*"; }
falha()  { printf '  \033[31mFALHA\033[0m  %s\n' "$*"; FALHOU=1; }
FALHOU=0

[ -n "$CHAVE" ] || { echo "falta a chave de ingestão: ./ferramentas/ensaio-sobrevivencia.sh <chave>"; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "sem dispositivo ligado"; exit 1; }

# Toca no centro do nó cujo texto, descrição ou identificador de recurso casa com o
# argumento. É isto que torna o guião igual nas duas versões apesar de o esquema
# ter mudado.
tocar_em() {
  local alvo="$1"
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r' > "$SAIDA/ui.xml"
  local coords
  coords=$(python3 ferramentas/no-central.py "$SAIDA/ui.xml" "$alvo")
  if [ -z "$coords" ]; then
    printf '  \033[33msalta\033[0m  não encontrei "%s" no ecrã\n' "$alvo"
    return 1
  fi
  "$ADB" shell input tap $coords >/dev/null 2>&1
  sleep 1
}

# A mesma tarefa nas duas versões: preencher o formulário, tentar pagar, e passar
# pelo ecrã em Compose, que é a outra árvore de interface.
tarefa() {
  "$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
  "$ADB" shell am start -n "$PACOTE/io.uxea.exemplo.LojaActivity" >/dev/null 2>&1
  sleep 6
  tocar_em "Nome no cartão"      && "$ADB" shell input text "Ana" >/dev/null 2>&1
  sleep 1
  tocar_em "Número do cartão"    && "$ADB" shell input text "4111111111111111" >/dev/null 2>&1
  sleep 1
  tocar_em "Validade"            && "$ADB" shell input text "1229" >/dev/null 2>&1
  sleep 1
  tocar_em "Pagar"
  tocar_em "Entrar na conta"
  tocar_em "Pedido que falha"
  tocar_em "Compose"
  sleep 3
  tocar_em "Nome"
  tocar_em "Pagar"
  tocar_em "Ajuda"
  "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 2
  tocar_em "Apoio"
  sleep 2
  "$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
  sleep 8   # tempo para a fila descarregar
}

saude() {
  curl -s "$INGESTAO/v1/elementos/saude?de=1.4.0-com-sdk&para=1.4.0-com-sdk-v2" -H "X-UXEA-Key: $CHAVE"
}

passo "1. a primeira versão"
./gradlew :exemplo:installComV1Debug -PuxeaChave="$CHAVE" -PuxeaServidor="$SERVIDOR" -q || { falha "não compilou a v1"; exit 1; }
ok "v1 instalada"
tarefa
ok "tarefa feita na v1"

passo "2. a segunda versão, com o esquema mudado"
"$ADB" uninstall "$PACOTE" >/dev/null 2>&1
./gradlew :exemplo:installComV2Debug -PuxeaChave="$CHAVE" -PuxeaServidor="$SERVIDOR" -q || { falha "não compilou a v2"; exit 1; }
ok "v2 instalada"
tarefa
ok "a mesma tarefa feita na v2"

passo "3. o que a ingestão reconheceu"
sleep 5
RESP=$(saude)
{
  echo "ensaio de sobrevivência de identidades, $(date -u +%FT%TZ)"
  echo "dispositivo: $("$ADB" shell getprop ro.product.model | tr -d '\r'), Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r'), $("$ADB" shell nproc | tr -d '\r') núcleo(s)"
  echo
  echo "$RESP" | python3 ferramentas/sobrevivencia-em-texto.py
} | tee "$SAIDA/sobrevivencia.txt"

TAXA=$(echo "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["dados"]["taxa_de_sobrevivencia"].split("%")[0])' 2>/dev/null)
echo
[ -n "$TAXA" ] && ok "taxa medida: ${TAXA}%" || falha "não deu para ler a taxa"
exit "$FALHOU"

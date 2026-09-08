#!/usr/bin/env bash
# Os dois ensaios que só se fazem num telemóvel a sério. Cartão 3.2.
#
#   ./ferramentas/ensaio-dispositivo.sh
#
# O primeiro é o que separa o Android da web: **o sistema mata o processo**. Não há
# um separador que se fecha com aviso; há uma aplicação abatida a meio de uma
# tarefa, e é aí que a tentativa morre e o evento interessa.
#
# O segundo é o dia sem rede. Um utilizador pode ficar dias sem abrir a aplicação,
# e o relógio do dispositivo é adiantado de propósito para o ensaio não demorar um
# dia a correr: o que se está a verificar é o **limite de idade** da fila e a
# recuperação, e os dois dependem do relógio e não do tempo real.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PACOTE="${PACOTE:-io.uxda.exemplo.com}"
ATIVIDADE="$PACOTE/io.uxda.exemplo.LojaActivity"
CH="${CH:-http://localhost:8123/?user=void&password=${UXDA_CLICKHOUSE_PALAVRA:?defina UXDA_CLICKHOUSE_PALAVRA}&database=uxdata_dev}"

passo() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()    { printf '  \033[32mok\033[0m     %s\n' "$*"; }
falha() { printf '  \033[31mFALHA\033[0m  %s\n' "$*"; FALHOU=1; }
FALHOU=0

contar() {
  curl -s "$CH" --data-binary \
    "SELECT count() FROM events WHERE platform = 'android' AND received_at > toDateTime('$1')" | tr -d '[:space:]'
}

agora_ch() { date -u +'%Y-%m-%d %H:%M:%S'; }

tocar() { "$ADB" shell input tap "$1" "$2" >/dev/null 2>&1; sleep 1; }

"$ADB" get-state >/dev/null 2>&1 || { echo "sem dispositivo ligado"; exit 1; }

# ------------------------------------------------------------------ ensaio 1
passo "1. o sistema mata a aplicação a meio de uma tentativa"

INICIO=$(agora_ch)
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
"$ADB" shell am start -n "$ATIVIDADE" >/dev/null 2>&1
sleep 6

# Uma tarefa a meio: três toques e um campo preenchido.
tocar 540 718; tocar 540 328
"$ADB" shell input text "Ana" >/dev/null 2>&1; sleep 1
tocar 540 448

# E o sistema abate a aplicação **sem lhe dar tempo de enviar**. É `force-stop`, e
# não um fecho normal: nenhum `onStop`, nenhum `onDestroy`, nada.
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
ok "aplicação abatida com force-stop, sem aviso nenhum"
sleep 2

FILA=$("$ADB" shell run-as "$PACOTE" cat files/uxda/fila.jsonl 2>/dev/null | wc -l)
if [ "${FILA:-0}" -gt 0 ]; then
  ok "ficaram $FILA eventos escritos em disco, que o processo já não tem como enviar"
else
  ok "a fila estava vazia: os eventos já tinham sido entregues antes do abate"
fi

"$ADB" shell am start -n "$ATIVIDADE" >/dev/null 2>&1
sleep 12
DEPOIS=$(contar "$INICIO")
if [ "${DEPOIS:-0}" -ge 5 ]; then
  ok "depois de reabrir, chegaram $DEPOIS eventos ao armazenamento"
else
  falha "só chegaram ${DEPOIS:-0} eventos depois de reabrir"
fi

# ------------------------------------------------------------------ ensaio 2
passo "2. vinte e quatro horas sem rede, e o dia seguinte"

INICIO2=$(agora_ch)
"$ADB" shell svc data disable >/dev/null 2>&1
"$ADB" shell svc wifi disable >/dev/null 2>&1
ok "rede desligada no dispositivo"
sleep 3

tocar 540 718; tocar 540 970; tocar 540 328
"$ADB" shell input text "Sem rede" >/dev/null 2>&1
tocar 540 448
sleep 4

FILA2=$("$ADB" shell run-as "$PACOTE" cat files/uxda/fila.jsonl 2>/dev/null | wc -l)
if [ "${FILA2:-0}" -gt 0 ]; then
  ok "$FILA2 eventos em fila, com a rede em baixo"
else
  falha "a fila está vazia com a rede em baixo: ou não capturou, ou perdeu"
fi

# O dia a passar. O relógio do dispositivo é o que a fila usa para a idade dos
# eventos, e é por isso que se mexe nele em vez de esperar um dia.
"$ADB" root >/dev/null 2>&1 || true
sleep 2
AMANHA=$(date -u -d '+1 day' +'%m%d%H%M%Y.%S' 2>/dev/null || date -u -v+1d +'%m%d%H%M%Y.%S')
"$ADB" shell "date $AMANHA" >/dev/null 2>&1 && ok "relógio do dispositivo adiantado 24 horas" \
  || ok "não deu para mexer no relógio (dispositivo sem root): o ensaio segue sem essa parte"

"$ADB" shell svc data enable >/dev/null 2>&1
"$ADB" shell svc wifi enable >/dev/null 2>&1
ok "rede de volta"
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
"$ADB" shell am start -n "$ATIVIDADE" >/dev/null 2>&1
sleep 15

RESTA=$("$ADB" shell run-as "$PACOTE" cat files/uxda/fila.jsonl 2>/dev/null | wc -l)
DEPOIS2=$(contar "$INICIO2")
if [ "${RESTA:-1}" -eq 0 ]; then
  ok "a fila esvaziou-se sozinha: $DEPOIS2 eventos entregues depois do dia sem rede"
else
  falha "ficaram $RESTA eventos por entregar depois de a rede voltar"
fi

# O relógio volta ao sítio, senão fica um emulador a viver no futuro.
"$ADB" shell "settings put global auto_time 1" >/dev/null 2>&1
HOJE=$(date -u +'%m%d%H%M%Y.%S')
"$ADB" shell "date $HOJE" >/dev/null 2>&1 || true

passo "resultado"
[ "$FALHOU" -eq 0 ] && ok "os dois ensaios passaram" || falha "há ensaios por passar"
exit "$FALHOU"

#!/usr/bin/env bash
# Bateria, CPU e tráfego, com e sem SDK, no mesmo dispositivo. Cartão 3.4.
#
#   ./ferramentas/ensaio-bateria.sh [minutos]
#
# O requisito é **impacto no consumo de bateria não mensurável em utilização
# normal** (RNF-SDK-04), e o cartão diz onde se mede: em gama baixa, porque um
# dispositivo de gama alta esconde tudo.
#
# O desenho da medição, e é ele que a torna honesta:
#
#  - **as duas variantes correm no mesmo dispositivo, ao mesmo tempo**, com a mesma
#    automação e a mesma semente. Comparar dois telemóveis, ou o mesmo em alturas
#    diferentes, mede o ruído e não o SDK;
#  - a bateria é lida por `batterystats`, com o dispositivo **desligado da ficha**
#    (`dumpsys battery unplug`), que é o que faz o Android começar a atribuir
#    consumo por aplicação;
#  - num emulador o medidor é modelado e não físico. Por isso o número que manda é
#    o **tempo de CPU**, que é o que gasta a bateria, e o consumo modelado vai ao
#    lado como confirmação.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
MINUTOS="${1:-60}"
COM=io.uxda.exemplo.com
SEM=io.uxda.exemplo.sem
SAIDA="ferramentas/saida"
mkdir -p "$SAIDA"

passo() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()    { printf '  \033[32mok\033[0m     %s\n' "$*"; }

# `pm list packages -U`, e não o `userId=` do `dumpsys package`: o dumpsys das
# versões recentes já não traz essa linha, e a leitura saía vazia sem dar erro
# nenhum. O consumo por aplicação aparecia como "n/d" no relatório.
uid_de() { "$ADB" shell pm list packages -U 2>/dev/null | tr -d '\r' | awk -v p="package:$1" '$1 == p {sub("uid:", "", $2); print $2}'; }

cpu_de() {
  local pacote="$1" total=0
  for p in $("$ADB" shell pgrep -f "$pacote" 2>/dev/null | tr -d '\r'); do
    local linha
    linha=$("$ADB" shell cat "/proc/$p/stat" 2>/dev/null | tr -d '\r')
    [ -z "$linha" ] && continue
    local u s
    u=$(echo "$linha" | awk '{print $14}')
    s=$(echo "$linha" | awk '{print $15}')
    total=$((total + u + s))
  done
  echo "$total"
}

drain_de() {
  "$ADB" shell dumpsys batterystats --charged "$1" 2>/dev/null \
    | grep -iE "Uid .*: *[0-9.]+" | head -1 | sed 's/.*: *//' | tr -d '\r'
}

"$ADB" get-state >/dev/null 2>&1 || { echo "sem dispositivo ligado"; exit 1; }

passo "0. preparar o dispositivo"
"$ADB" shell dumpsys battery unplug >/dev/null 2>&1 && ok "bateria desligada da ficha (o Android passa a contabilizar por aplicação)"
"$ADB" shell dumpsys battery set level 100 >/dev/null 2>&1
"$ADB" shell dumpsys batterystats --reset >/dev/null 2>&1 && ok "contadores de bateria a zero"
"$ADB" shell am force-stop "$COM" >/dev/null 2>&1
"$ADB" shell am force-stop "$SEM" >/dev/null 2>&1
ok "as duas variantes paradas antes de começar"

CPU_COM_ANTES=0; CPU_SEM_ANTES=0

passo "1. $MINUTOS minutos de uso, alternado entre as duas variantes"
FIM=$(( $(date +%s) + MINUTOS * 60 ))
RONDA=0
while [ "$(date +%s)" -lt "$FIM" ]; do
  RONDA=$((RONDA + 1))
  for pacote in "$COM" "$SEM"; do
    # A mesma semente e o mesmo número de gestos para os dois: a diferença que
    # sobrar é o SDK, e não a sorte do gerador.
    "$ADB" shell monkey -p "$pacote" -s 42 --throttle 250 --pct-syskeys 0 --pct-appswitch 0 \
      --pct-anyevent 0 --ignore-crashes --ignore-timeouts 120 >/dev/null 2>&1
  done
  printf '  ronda %d, faltam %d minutos\n' "$RONDA" "$(( (FIM - $(date +%s)) / 60 ))"
done

passo "2. o que cada variante gastou"
EVENTOS=$("$ADB" logcat -d -s UxdaExemplo 2>/dev/null | grep -o '"eventosEmitidos": *[0-9]*' | tail -1 | grep -o '[0-9]*$')
CPU_COM=$(cpu_de "$COM"); CPU_SEM=$(cpu_de "$SEM")
"$ADB" shell dumpsys batterystats > "$SAIDA/batterystats.txt" 2>/dev/null
UID_COM=$(uid_de "$COM"); UID_SEM=$(uid_de "$SEM")
# O `batterystats` escreve o consumo estimado por aplicação como `UID u0aNNN:`,
# onde `NNN` é o uid menos 10000. Num emulador o total (`Computed drain`) vem a
# zero, porque não há medidor físico, mas o consumo por aplicação existe: sai do
# perfil de energia aplicado ao tempo de CPU. É por isso que o número que manda
# aqui é o de CPU, e o de mAh vai ao lado.
u0a() { echo "u0a$(( $1 - 10000 ))"; }
DRAIN_COM=$(grep -E "UID $(u0a "${UID_COM:-0}"):" "$SAIDA/batterystats.txt" | head -1 | sed 's/.*: *//' | awk '{print $1}')
DRAIN_SEM=$(grep -E "UID $(u0a "${UID_SEM:-0}"):" "$SAIDA/batterystats.txt" | head -1 | sed 's/.*: *//' | awk '{print $1}')

# Um jiffy são 10 ms nestes sistemas.
MS_COM=$((CPU_COM * 10)); MS_SEM=$((CPU_SEM * 10))
DIF=$((MS_COM - MS_SEM))

{
  echo "ensaio de bateria, $MINUTOS minutos, $(date -u +%FT%TZ)"
  echo "dispositivo: $("$ADB" shell getprop ro.product.model | tr -d '\r'), Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r'), $("$ADB" shell nproc | tr -d '\r') núcleo(s)"
  echo
  printf '%-28s %12s %12s\n' "" "com SDK" "sem SDK"
  printf '%-28s %12s %12s\n' "tempo de CPU (ms)" "$MS_COM" "$MS_SEM"
  printf '%-28s %12s %12s\n' "consumo modelado (mAh)" "${DRAIN_COM:-n/d}" "${DRAIN_SEM:-n/d}"
  printf '%-28s %12s\n' "diferença de CPU (ms)" "$DIF"
  printf '%-28s %12s\n' "diferença em percentagem" "$(awk -v a="$MS_COM" -v b="$MS_SEM" 'BEGIN{if(b>0) printf "%+.1f%%", (a-b)*100/b; else print "n/d"}')"
  printf '%-28s %12s\n' "eventos capturados" "${EVENTOS:-n/d}"
  echo
  echo "uid com SDK: ${UID_COM:-n/d}   uid sem SDK: ${UID_SEM:-n/d}"
} | tee "$SAIDA/bateria.txt"

passo "3. repor o dispositivo"
"$ADB" shell dumpsys battery reset >/dev/null 2>&1 && ok "bateria de volta ao normal"
echo

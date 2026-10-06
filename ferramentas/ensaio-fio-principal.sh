#!/usr/bin/env bash
# Quanto tempo o SDK rouba ao fio principal da interface. Cartão 3.4, RNF-SDK-02.
#
#   ./ferramentas/ensaio-fio-principal.sh [toques]
#
# O requisito diz **nenhuma operação no fio de execução principal**, e à letra isso
# é impossível: a leitura do toque tem de acontecer onde o toque acontece. O que se
# mede é o que fica lá, e o orçamento é o de uma trama: 16,7 ms a 60 Hz. Se um
# toque nosso custar mais do que uma fração disso, a aplicação de quem nos instala
# treme, e quem trema é ela e não nós.
#
# O SDK conta esse tempo por dentro (`msNoFioPrincipal` no diagnóstico), e a
# aplicação de ensaio escreve o diagnóstico no registo do sistema de dois em dois
# segundos: é de lá que sai o número, sem ninguém o transcrever de uma captura.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PACOTE="${PACOTE:-io.uxea.exemplo.com}"
ATIVIDADE="$PACOTE/io.uxea.exemplo.LojaActivity"
TOQUES="${1:-200}"
LIMITE_MS_POR_EVENTO="1"

"$ADB" get-state >/dev/null 2>&1 || { echo "sem dispositivo ligado"; exit 1; }

echo
echo "tempo no fio principal, $TOQUES toques"
echo

"$ADB" shell am force-stop io.uxea.exemplo.sem >/dev/null 2>&1
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell am start -n "$ATIVIDADE" >/dev/null 2>&1
sleep 6

for _ in $(seq 1 "$TOQUES"); do
  "$ADB" shell input tap 540 718 >/dev/null 2>&1
done
sleep 4

# A linha da variante **com** SDK, e não a última que lá estiver: a variante sem
# SDK escreve o diagnóstico dela no mesmo registo de dois em dois segundos.
LINHA=$("$ADB" logcat -d -s UxeaExemplo 2>/dev/null | grep '"msNoFioPrincipal"' | tail -1)
MS=$(echo "$LINHA" | grep -o '"msNoFioPrincipal": *[0-9.]*' | grep -o '[0-9.]*$')
EVENTOS=$(echo "$LINHA" | grep -o '"eventosEmitidos": *[0-9]*' | grep -o '[0-9]*$')
ERROS=$(echo "$LINHA" | grep -o '"errosInternos": *[0-9]*' | grep -o '[0-9]*$')

if [ -z "${MS:-}" ] || [ -z "${EVENTOS:-}" ] || [ "${EVENTOS:-0}" -eq 0 ]; then
  echo "  FALHA  não saiu diagnóstico do dispositivo (a variante instalada é a com SDK?)"
  exit 1
fi

POR_EVENTO=$(awk -v ms="$MS" -v n="$EVENTOS" 'BEGIN{printf "%.3f", ms/n}')
MODELO=$("$ADB" shell getprop ro.product.model | tr -d '\r')
# `nproc`, e não `grep -c processor /proc/cpuinfo`: neste emulador o
# `/proc/cpuinfo` traz a linha `model name: Android virtual processor`, que a
# contagem apanhava, e um dispositivo de um núcleo aparecia com dois.
NUCLEOS=$("$ADB" shell nproc | tr -d '\r')

printf '  dispositivo                      %s, %s núcleo(s)\n' "$MODELO" "$NUCLEOS"
printf '  eventos capturados               %s\n' "$EVENTOS"
printf '  tempo total no fio principal     %s ms\n' "$MS"
printf '  por evento capturado             %s ms   limite %s ms\n' "$POR_EVENTO" "$LIMITE_MS_POR_EVENTO"
printf '  erros internos                   %s\n' "${ERROS:-0}"
echo

awk -v p="$POR_EVENTO" -v l="$LIMITE_MS_POR_EVENTO" 'BEGIN{exit !(p+0 < l+0)}'

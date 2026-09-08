#!/usr/bin/env bash
# A aplicação anfitriã com o SDK a falhar de propósito. Cartão 3.4, RNF-SDK-01.
#
#   ./ferramentas/ensaio-falhas-dispositivo.sh
#
# Os ensaios de injeção de falhas correm na máquina virtual e partem o SDK por
# dentro. Este parte-o **por fora, num telemóvel**, que é como ele se parte na vida
# real: o armazenamento enche, o sistema revoga uma permissão, um ficheiro fica a
# meio de uma escrita, a rede desaparece.
#
# O que se exige: a aplicação continua a funcionar, e o utilizador não vê nada.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PACOTE="${PACOTE:-io.uxda.exemplo.com}"
ATIVIDADE="$PACOTE/io.uxda.exemplo.LojaActivity"

passo() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()    { printf '  \033[32mok\033[0m     %s\n' "$*"; }
falha() { printf '  \033[31mFALHA\033[0m  %s\n' "$*"; FALHOU=1; }
FALHOU=0

viva() { "$ADB" shell dumpsys activity activities 2>/dev/null | grep -q "topResumedActivity.*$PACOTE"; }
usar() { for y in 718 328 448 566 844 970; do "$ADB" shell input tap 540 "$y" >/dev/null 2>&1; sleep 0.4; done; }

# A outra variante fica a escrever o diagnóstico dela no mesmo registo de dois em
# dois segundos, e a última linha passava a ser a dela.
"$ADB" shell am force-stop io.uxda.exemplo.sem >/dev/null 2>&1
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1

# **Sem `run-as` este ensaio não parte nada**, e passa a dizer que a aplicação
# sobreviveu a tudo por não lhe ter acontecido nada. O `run-as` só funciona numa
# variante de depuração, e numa variante de lançamento falha em silêncio.
if ! "$ADB" shell run-as "$PACOTE" true >/dev/null 2>&1; then
  echo "  FALHA  o run-as não funciona: a variante instalada não é de depuração."
  echo "         ./gradlew :exemplo:installComV1Debug -PuxdaChave=<chave>"
  exit 1
fi

"$ADB" logcat -c >/dev/null 2>&1
"$ADB" shell am start -n "$ATIVIDADE" >/dev/null 2>&1
sleep 6
viva && ok "aplicação a correr antes de partir seja o que for" || falha "a aplicação não arrancou"

passo "1. o ficheiro da fila fica a meio de uma escrita"
"$ADB" shell "run-as $PACOTE sh -c 'printf \"{lixo a meio\" >> files/uxda/fila.jsonl'" >/dev/null 2>&1
usar; sleep 2
viva && ok "a aplicação sobreviveu a uma fila corrompida" || falha "a aplicação morreu com a fila corrompida"

passo "2. o SDK deixa de poder escrever no disco"
"$ADB" shell "run-as $PACOTE sh -c 'chmod 000 files/uxda'" >/dev/null 2>&1
usar; usar; sleep 2
viva && ok "a aplicação sobreviveu ao disco fechado" || falha "a aplicação morreu sem poder escrever"

passo "3. e sem rede nenhuma por cima disso"
"$ADB" shell svc data disable >/dev/null 2>&1
"$ADB" shell svc wifi disable >/dev/null 2>&1
usar; sleep 3
viva && ok "a aplicação sobreviveu sem rede e sem disco" || falha "a aplicação morreu sem rede"

passo "4. o que o sistema registou"
FATAIS=$("$ADB" logcat -d 2>/dev/null | grep -cE "FATAL EXCEPTION|AndroidRuntime.*$PACOTE" || true)
if [ "${FATAIS:-0}" -eq 0 ]; then
  ok "nenhuma exceção fatal no registo do sistema"
else
  falha "$FATAIS exceções fatais no registo"
  "$ADB" logcat -d | grep -A6 "FATAL EXCEPTION" | head -20
fi

ERROS=$("$ADB" logcat -d -s UxdaExemplo 2>/dev/null | grep '"errosInternos"' | tail -1 | grep -o '"errosInternos": *[0-9]*' | grep -o '[0-9]*$')
# **Zero erros internos aqui é uma falha, e não um bom sinal.** Quer dizer que
# nada do que se partiu chegou a ser tocado, e o ensaio estaria a dizer que a
# aplicação sobreviveu a uma coisa que não lhe aconteceu.
if [ -n "${ERROS:-}" ] && [ "$ERROS" -gt 0 ]; then
  ok "o SDK apanhou $ERROS erros internos por dentro, e engoliu-os todos"
else
  falha "o SDK registou ${ERROS:-0} erros internos: as falhas não chegaram a ser injetadas"
fi

passo "5. repor o dispositivo"
"$ADB" shell "run-as $PACOTE sh -c 'chmod 700 files/uxda'" >/dev/null 2>&1
"$ADB" shell svc data enable >/dev/null 2>&1
"$ADB" shell svc wifi enable >/dev/null 2>&1
ok "disco e rede de volta"

passo "resultado"
[ "$FALHOU" -eq 0 ] && ok "a anfitriã sobreviveu a tudo" || falha "há ensaios por passar"
exit "$FALHOU"

#!/usr/bin/env bash
# Os quatro casos do cartão 4.1, e o agregado por campo do 4.2, num telemóvel.
#
#   ./ferramentas/ensaio-granular.sh
#
# O que isto prova é o que nenhuma ferramenta de funis mostra: um funil vê os
# passos que aconteceram, e um toque numa zona morta não é um passo.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PACOTE="${PACOTE:-io.uxea.exemplo.com}"
CH="${CH:-http://localhost:8123/?user=void&password=${UXEA_CLICKHOUSE_PALAVRA:?defina UXEA_CLICKHOUSE_PALAVRA}&database=uxea_dev}"
SAIDA="ferramentas/saida"
mkdir -p "$SAIDA"

ok()    { printf '  \033[32mok\033[0m     %s\n' "$*"; }
falha() { printf '  \033[31mFALHA\033[0m  %s\n' "$*"; FALHOU=1; }
FALHOU=0

tocar_em() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r' > "$SAIDA/ui.xml"
  local c; c=$(python3 ferramentas/no-central.py "$SAIDA/ui.xml" "$1")
  [ -z "$c" ] && { printf '  \033[33msalta\033[0m  sem "%s" no ecrã\n' "$1"; return 1; }
  "$ADB" shell input tap $c >/dev/null 2>&1
  sleep 1
}

"$ADB" get-state >/dev/null 2>&1 || { echo "sem dispositivo ligado"; exit 1; }

printf '\n\033[1mcaptura granular, num emulador de gama baixa\033[0m\n'
INICIO=$(date -u +'%Y-%m-%d %H:%M:%S')
"$ADB" shell am force-stop io.uxea.exemplo.sem >/dev/null 2>&1
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
"$ADB" shell am start -n "$PACOTE/io.uxea.exemplo.LojaActivity" >/dev/null 2>&1
sleep 6

# 1. Uma zona que parece acionável e não é.
tocar_em "Precisa de ajuda" && ok "toque numa zona morta"
# 2. Um botão desativado, e ninguém lhe disse porquê.
tocar_em "Confirmar encomenda" && ok "toque num botão desativado"
# 3. Insistir num botão que não responde, com a aplicação ocupada.
#
# Os toques repetidos vão **às mesmas coordenadas e depressa**, sem voltar a ler a
# árvore de interface: uma leitura demora mais de um segundo, e a rajada define-se
# por ser rápida. A primeira versão deste guião lia a árvore entre toques, e por
# isso nunca havia rajada nenhuma para detetar.
"$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
"$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r' > "$SAIDA/ui.xml"
COORD_LENTO=$(python3 ferramentas/no-central.py "$SAIDA/ui.xml" "Botão lento")
if [ -n "$COORD_LENTO" ]; then
  "$ADB" shell input tap $COORD_LENTO >/dev/null 2>&1
  ok "botão lento carregado: a aplicação fica ocupada"
  for _ in 1 2 3; do
    "$ADB" shell input tap $COORD_LENTO >/dev/null 2>&1
    sleep 0.3
  done
  ok "três insistências enquanto ele não responde"
else
  falha "não encontrei o botão lento"
fi
sleep 3

# 4. Preencher, com uma correção pelo meio, e submeter.
tocar_em "Nome no cartão"   && "$ADB" shell input text "Ana" >/dev/null 2>&1
tocar_em "Número do cartão" && "$ADB" shell input text "411111" >/dev/null 2>&1
"$ADB" shell input keyevent KEYCODE_DEL KEYCODE_DEL >/dev/null 2>&1
"$ADB" shell input text "9999" >/dev/null 2>&1
tocar_em "Validade" && "$ADB" shell input text "1229" >/dev/null 2>&1
"$ADB" shell input keyevent KEYCODE_ENTER >/dev/null 2>&1
ok "formulário preenchido, com uma correção pelo meio"
sleep 2
tocar_em "Pagar 12" && ok "submissão"
sleep 8

printf '\n\033[1mo que chegou ao armazenamento\033[0m\n\n'
{
  echo "ensaio de captura granular, $(date -u +%FT%TZ)"
  echo "dispositivo: $("$ADB" shell getprop ro.product.model | tr -d '\r'), Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r'), $("$ADB" shell nproc | tr -d '\r') núcleo(s)"
  echo
  curl -s "$CH" --data-binary "
SELECT event_type, count() AS n, any(properties) AS propriedades
FROM events
WHERE platform = 'android' AND received_at > toDateTime('$INICIO')
GROUP BY event_type ORDER BY event_type FORMAT TSVWithNames"
} | tee "$SAIDA/granular.txt"

for tipo in toque_sem_alvo toque_desativado toque_repetido toque_em_carregamento campo primeira_interacao; do
  if grep -q "^$tipo	" "$SAIDA/granular.txt"; then ok "$tipo"; else falha "faltou o $tipo"; fi
done

printf '\n\033[1mresultado\033[0m\n'
[ "$FALHOU" -eq 0 ] && ok "os quatro casos aparecem separados, e o agregado por campo saiu" || falha "há sinais em falta"
exit "$FALHOU"

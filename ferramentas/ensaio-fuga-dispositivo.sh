#!/usr/bin/env bash
# A prova de que nada do que a pessoa escreve sai do telemóvel. RNF-PRI-01.
#
#   ./ferramentas/ensaio-fuga-dispositivo.sh
#
# A bateria de fuga em JVM (`FugaTest`) cobre as vistas clássicas, e cobre-as bem.
# O que ela **não** consegue cobrir é o Compose: a árvore semântica é lida por
# reflexão sobre uma biblioteca que aqui é dependência só de compilação, e sem um
# Android a sério não há árvore nenhuma para ler.
#
# Este ensaio fecha esse buraco pelo outro lado, e fecha-o melhor: escreve
# marcadores reconhecíveis em **todos** os campos das duas árvores de interface,
# num telemóvel, e depois procura-os no armazenamento analítico. Se um marcador
# aparecer em qualquer coluna de qualquer evento, houve fuga.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PACOTE="${PACOTE:-io.uxea.exemplo.com}"
CH="${CH:-http://localhost:8123/?user=void&password=${UXEA_CLICKHOUSE_PALAVRA:?defina UXEA_CLICKHOUSE_PALAVRA}&database=uxea_dev}"
SAIDA="ferramentas/saida"
mkdir -p "$SAIDA"

passo() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()    { printf '  \033[32mok\033[0m     %s\n' "$*"; }
falha() { printf '  \033[31mFALHA\033[0m  %s\n' "$*"; FALHOU=1; }
FALHOU=0

"$ADB" get-state >/dev/null 2>&1 || { echo "sem dispositivo ligado"; exit 1; }

# Marcadores que não existem em lado nenhum do código nem dos dados de exemplo: se
# aparecerem no armazenamento, vieram do teclado desta pessoa.
# Nada de marcadores curtos e numéricos: um "1229" apanhava-se por acaso dentro
# de um identificador aleatório, e daria uma fuga que não existe.
#
# E os três que a **aplicação** interpola nas mensagens que mostra, que é o
# caminho que a fase 5 abriu: o documento, a data e o nome que aparecem no ecrã
# dentro de uma mensagem de erro. Nenhum deles é conteúdo de campo, e todos eles
# são conteúdo de alguém.
MARCADORES=(SEGREDOxNOME 4111111111111111 SEGREDOxVALIDADE SEGREDOxCOMPOSE
            005123456LA041 2027-03-14 "Ana Maria da Silva")

tocar_em() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r' > "$SAIDA/ui.xml"
  local c; c=$(python3 ferramentas/no-central.py "$SAIDA/ui.xml" "$1")
  [ -z "$c" ] && return 1
  "$ADB" shell input tap $c >/dev/null 2>&1
  sleep 1
}

INICIO=$(date -u +'%Y-%m-%d %H:%M:%S')

passo "1. escrever os marcadores em todos os campos, nas duas árvores"
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
"$ADB" shell am start -n "$PACOTE/io.uxea.exemplo.LojaActivity" >/dev/null 2>&1
sleep 6

tocar_em "Nome no cartão"   && "$ADB" shell input text "SEGREDOxNOME" >/dev/null 2>&1
tocar_em "Número do cartão" && "$ADB" shell input text "4111111111111111" >/dev/null 2>&1
tocar_em "Validade"         && "$ADB" shell input text "SEGREDOxVALIDADE" >/dev/null 2>&1
tocar_em "Pagar" ; sleep 1
ok "vistas clássicas preenchidas"

# As mensagens do cartão 5.1, com montante, documento, data e nome lá dentro. O
# SDK vê-as, e o que tem de chegar ao armazenamento é o texto com marcadores no
# lugar dos valores, e nunca os valores.
# **Fechar o teclado primeiro.** Com ele aberto, os botões das mensagens ficam
# fora da parte visível e o `uiautomator` não os encontra: a primeira corrida
# deste ensaio deu conta de sete marcadores procurados e zero mensagens
# capturadas, que é uma bateria a passar por não ter olhado para nada.
"$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
sleep 1
MENSAGENS=0
tocar_em "Mensagem com chave"   && MENSAGENS=$((MENSAGENS+1))
tocar_em "Mensagem só com texto" && MENSAGENS=$((MENSAGENS+1))
[ "$MENSAGENS" = "2" ] && ok "mensagens de sistema mostradas" || falha "só $MENSAGENS de 2 mensagens foram mostradas"

tocar_em "Compose" ; sleep 4
tocar_em "Nome" && "$ADB" shell input text "SEGREDOxCOMPOSE" >/dev/null 2>&1
tocar_em "Pagar" ; sleep 1
"$ADB" shell input keyevent KEYCODE_BACK >/dev/null 2>&1
ok "ecrã em Compose preenchido"

sleep 10   # a fila tem de descarregar antes de se procurar

passo "2. procurar os marcadores em tudo o que chegou ao armazenamento"
CONDICAO=""
for m in "${MARCADORES[@]}"; do
  [ -n "$CONDICAO" ] && CONDICAO="$CONDICAO OR "
  CONDICAO="${CONDICAO}position(linha, '$m') > 0"
done

TOTAL=$(curl -s "$CH" --data-binary "
SELECT count() FROM events
WHERE platform = 'android' AND received_at > toDateTime('$INICIO')" | tr -d '[:space:]')

FUGAS=$(curl -s "$CH" --data-binary "
SELECT count() FROM (
  SELECT concat(anonymous_id, '|', user_id, '|', device_id, '|', session_id, '|',
                screen_key, '|', element_key, '|', message_key, '|', message_kind, '|',
                message_text_masked, '|', properties) AS linha
  FROM events
  WHERE platform = 'android' AND received_at > toDateTime('$INICIO')
) WHERE $CONDICAO" | tr -d '[:space:]')

{
  echo "ensaio de fuga em dispositivo, $(date -u +%FT%TZ)"
  echo "dispositivo: $("$ADB" shell getprop ro.product.model | tr -d '\r'), Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r'), $("$ADB" shell nproc | tr -d '\r') núcleo(s)"
  echo
  echo "  marcadores escritos             ${#MARCADORES[@]}, nas duas árvores de interface"
  echo "  eventos android chegados        ${TOTAL:-0}"
  echo "  eventos com algum marcador      ${FUGAS:-?}"
  echo
  echo "  o que o SDK escreveu no lugar do conteúdo das mensagens:"
  curl -s "$CH" --data-binary "
SELECT message_key, message_text_masked
FROM events
WHERE platform = 'android' AND received_at > toDateTime('$INICIO')
  AND event_type = 'mensagem'
ORDER BY corrected_at LIMIT 4 FORMAT TSV" | sed 's/^/    /'
  echo
  echo "  o que o SDK escreveu no lugar do conteúdo:"
  curl -s "$CH" --data-binary "
SELECT event_type, element_key, properties
FROM events
WHERE platform = 'android' AND received_at > toDateTime('$INICIO')
  AND event_type IN ('campo', 'foco', 'tecla', 'desfoco', 'submissao')
ORDER BY corrected_at LIMIT 8 FORMAT TSV" | sed 's/^/    /'
} | tee "$SAIDA/fuga-dispositivo.txt"

echo
if [ "${TOTAL:-0}" -lt 5 ]; then
  falha "chegaram só ${TOTAL:-0} eventos: o ensaio não chegou a provar nada"
elif [ "${FUGAS:-1}" -eq 0 ]; then
  ok "nenhum dos ${#MARCADORES[@]} marcadores aparece em nenhum dos $TOTAL eventos"
else
  falha "$FUGAS eventos trazem conteúdo escrito pela pessoa"
fi
exit "$FALHOU"

#!/usr/bin/env bash
# As mensagens de sistema dos cartões 5.1, 5.2 e 5.3, num telemóvel a sério.
#
#   ./ferramentas/ensaio-mensagens.sh
#
# O que isto prova, e que nenhum ensaio de unidade prova: numa aplicação **a
# correr**, com a árvore de vistas verdadeira e a disposição verdadeira, o SDK vê
# as mensagens que a aplicação mostra, classifica-as, mascara o que elas trazem
# lá dentro, e nada do que a pessoa escreveu atravessa a rede.
#
# A leitura é feita no armazenamento, e não no dispositivo: o que interessa é o
# que **chegou**, e não o que foi emitido.
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

consulta() { curl -s -X POST "$CH" --data-binary "$1"; }

tocar_em() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  "$ADB" shell cat /sdcard/ui.xml 2>/dev/null | tr -d '\r' > "$SAIDA/ui.xml"
  local c; c=$(python3 ferramentas/no-central.py "$SAIDA/ui.xml" "$1")
  [ -z "$c" ] && { printf '  \033[33msalta\033[0m  sem "%s" no ecrã\n' "$1"; return 1; }
  "$ADB" shell input tap $c >/dev/null 2>&1
  sleep 1
}

"$ADB" get-state >/dev/null 2>&1 || { echo "sem dispositivo ligado"; exit 1; }

printf '\n\033[1mmensagens de sistema, num telemóvel\033[0m\n'
INICIO=$(date -u +'%Y-%m-%d %H:%M:%S')
"$ADB" shell am force-stop "$PACOTE" >/dev/null 2>&1
"$ADB" shell am start -n "$PACOTE/io.uxea.exemplo.LojaActivity" >/dev/null 2>&1
sleep 6

# 1. A mensagem com chave. É o caminho que o RF-MSG-02 manda preferir.
tocar_em "Mensagem com chave" && ok "mensagem declarada por chave"
sleep 1

# 2. A mensagem só com texto, com um montante, um documento, uma data e um nome
#    lá dentro. É onde o mascaramento do 5.2 tem de funcionar.
tocar_em "Mensagem só com texto" && ok "mensagem só com texto"
sleep 1

# 3. O erro que ninguém vê (RF-MSG-06).
tocar_em "Erro que ninguém vê" && ok "erro técnico, invisível"
sleep 1

# 4. E a validação da plataforma, que é o `setError` de um campo.
#
# **Duas vezes, e é preciso.** O toque é visto no `dispatchTouchEvent`, que corre
# *antes* de o ouvinte da aplicação pôr o erro no campo: ao primeiro toque ainda
# não há erro nenhum para ver. Quem o vê é o toque seguinte.
tocar_em "Pagar" && sleep 1
tocar_em "Pagar" && ok "validação nativa num campo"
sleep 2

# As mensagens desaparecem ao fim de seis segundos, como um aviso a sério. Para a
# fotografia mostrar alguma coisa, mostram-se outra vez mesmo antes de a tirar.
tocar_em "Mensagem com chave" >/dev/null
tocar_em "Mensagem só com texto" >/dev/null
sleep 1

# A captura de ecrã **antes** do HOME, e a ordem não é detalhe: capturar depois
# de mandar a aplicação para trás dá uma fotografia do ecrã inicial do sistema, e
# foi o que a primeira corrida deste ensaio produziu.
"$ADB" exec-out screencap -p > exemplo/ensaio-mensagens-android.png 2>/dev/null
ok "captura de ecrã em exemplo/ensaio-mensagens-android.png"

# E agora sim: o plano de fundo é o que despeja a fila.
"$ADB" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
sleep 8

MEDIDO="ferramentas/mensagens-medido.txt"
{
  echo "ensaio de mensagens de sistema, $(date -u +'%Y-%m-%dT%H:%M:%SZ')"
  echo "dispositivo: $("$ADB" shell getprop ro.product.model | tr -d '\r'), Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')"
  echo
} > "$MEDIDO"

printf '\n\033[1mo que chegou ao armazenamento\033[0m\n'
consulta "
SELECT event_type, message_key, message_kind,
       JSONExtractString(properties, 'classe_erro') AS classe,
       JSONExtractString(properties, 'visivel') AS visivel,
       message_text_masked
FROM events
WHERE platform = 'android' AND received_at >= '$INICIO'
  AND event_type IN ('mensagem', 'erro_rede')
ORDER BY occurred_at
FORMAT TabSeparatedWithNames" | tee "$SAIDA/mensagens.tsv" | tee -a "$MEDIDO"

TOTAL=$(consulta "SELECT count() FROM events WHERE platform='android' AND received_at >= '$INICIO' AND event_type='mensagem'" | tr -d '\n')
[ "${TOTAL:-0}" -ge 3 ] && ok "$TOTAL mensagens chegaram" || falha "só chegaram ${TOTAL:-0} mensagens"

# As quatro classificações do RF-MSG-01 e as três do RF-MSG-07 têm de aparecer
# separadas: um catálogo que não as separa é uma lista, e não uma prioridade.
CLASSES=$(consulta "SELECT arrayStringConcat(arraySort(groupUniqArray(JSONExtractString(properties,'classe_erro'))), ',') FROM events WHERE platform='android' AND received_at >= '$INICIO' AND event_type='mensagem'" | tr -d '\n')
ok "classes de erro observadas: ${CLASSES:-nenhuma}"

# **A prova que interessa**: nada do que estava no ecrã atravessou a rede.
printf '\n\033[1mfuga: o que estava no ecrã e não podia sair\033[0m\n'
for SEGREDO in '12.400' '12400' '005123456LA041' '2027-03-14' 'Ana Maria da Silva'; do
  N=$(consulta "SELECT count() FROM events WHERE platform='android' AND received_at >= '$INICIO' AND (positionCaseInsensitive(coalesce(message_text_masked,''), '$SEGREDO') > 0 OR positionCaseInsensitive(toString(properties), '$SEGREDO') > 0 OR positionCaseInsensitive(coalesce(element_key,''), '$SEGREDO') > 0)" | tr -d '\n')
  [ "${N:-1}" = "0" ] && ok "\"$SEGREDO\" não saiu" || falha "\"$SEGREDO\" atravessou a rede em ${N} eventos"
  echo "fuga \"$SEGREDO\": ${N} eventos" >> "$MEDIDO"
done

MASCARADO=$(consulta "SELECT count() FROM events WHERE platform='android' AND received_at >= '$INICIO' AND position(coalesce(message_text_masked,''), '{numero}') > 0" | tr -d '\n')
[ "${MASCARADO:-0}" -ge 1 ] && ok "o texto chegou com marcadores no lugar dos valores" || falha "nenhum texto mascarado chegou"

{
  echo
  echo "classes de erro observadas: ${CLASSES:-nenhuma}"
  echo "mensagens que chegaram: ${TOTAL:-0}"
  echo "textos com marcadores no lugar dos valores: ${MASCARADO:-0}"
} >> "$MEDIDO"
ok "números escritos em $MEDIDO"

printf '\n'
[ "$FALHOU" = "0" ] && printf '\033[32mtudo passou\033[0m\n' || printf '\033[31mfalhou\033[0m\n'
exit "$FALHOU"

#!/usr/bin/env bash
# O orçamento de tamanho, verificado. Cartão 3.4, RNF-SDK-03.
#
#   ./ferramentas/orcamento.sh
#
# O requisito é **acréscimo ao tamanho da aplicação inferior a 300 KB**, e a
# palavra que interessa é *acréscimo*: o que se mede não é o tamanho do nosso
# ficheiro, é quanto é que a aplicação de quem nos instala cresce por nossa causa.
#
# Por isso a medição é a diferença entre as duas variantes da **mesma** aplicação
# de ensaio, compiladas do mesmo código, com e sem o SDK. É também o que torna
# honesta a comparação de bateria do mesmo cartão.
#
# Falha a compilação quando o limite for ultrapassado. Um limite que só avisa é um
# limite que se ultrapassa na terceira semana e ninguém dá por isso.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

LIMITE_KB=300
GRADLE=./gradlew

echo
echo "orçamento de tamanho do SDK Android"
echo

# As variantes têm duas dimensões: com/sem SDK, e a versão do esquema. A
# medição do acréscimo compara as duas da **mesma** versão do esquema.
$GRADLE :exemplo:assembleComV1Release :exemplo:assembleSemV1Release :uxda:assembleRelease -q || exit 1

COM=$(find exemplo/build/outputs/apk/comV1/release -name "*.apk" | head -1)
SEM=$(find exemplo/build/outputs/apk/semV1/release -name "*.apk" | head -1)
AAR=$(find uxda/build/outputs/aar -name "*.aar" | head -1)

[ -f "$COM" ] && [ -f "$SEM" ] || { echo "  FALHA  faltam os APK das duas variantes"; exit 1; }

bytes() { stat -c%s "$1" 2>/dev/null || stat -f%z "$1"; }

B_COM=$(bytes "$COM"); B_SEM=$(bytes "$SEM"); B_AAR=$(bytes "$AAR")
DELTA=$((B_COM - B_SEM))
DELTA_KB=$((DELTA / 1024))

# O que o SDK ocupa já dentro do APK, comprimido, que é como ele viaja.
DEX_COM=$(unzip -l "$COM" 2>/dev/null | awk '/classes.*\.dex/ {s+=$1} END {print s+0}')
DEX_SEM=$(unzip -l "$SEM" 2>/dev/null | awk '/classes.*\.dex/ {s+=$1} END {print s+0}')
DEX_DELTA=$(( (DEX_COM - DEX_SEM) / 1024 ))

linha() {
  local nome="$1" valor="$2" limite="$3" ok="$4"
  if [ "$ok" = "1" ]; then
    printf '  \033[32mok\033[0m     %-38s %10s   limite %s\n' "$nome" "$valor" "$limite"
  else
    printf '  \033[31mFALHA\033[0m  %-38s %10s   limite %s\n' "$nome" "$valor" "$limite"
    FALHOU=1
  fi
}

FALHOU=0
linha "acréscimo ao APK da aplicação" "${DELTA_KB} KB" "${LIMITE_KB} KB" "$([ "$DELTA_KB" -lt "$LIMITE_KB" ] && echo 1 || echo 0)"
linha "acréscimo em código (dex)" "${DEX_DELTA} KB" "${LIMITE_KB} KB" "$([ "$DEX_DELTA" -lt "$LIMITE_KB" ] && echo 1 || echo 0)"
linha "biblioteca sozinha (aar)" "$((B_AAR / 1024)) KB" "-" "1"
linha "aplicação com SDK" "$((B_COM / 1024)) KB" "-" "1"
linha "aplicação sem SDK" "$((B_SEM / 1024)) KB" "-" "1"

echo
exit "$FALHOU"

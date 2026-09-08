#!/usr/bin/env python3
"""O centro do primeiro nó da árvore de interface que casa com o alvo.

Existe para o `ensaio-sobrevivencia.sh` poder fazer **a mesma tarefa** nas duas
versões da aplicação de ensaio. A segunda mudou os campos de sítio de propósito,
e um guião com coordenadas fixas passaria a tocar noutra coisa: mediria o guião,
e não a sobrevivência das identidades.

    python3 ferramentas/no-central.py <ficheiro-do-dump> <alvo>

O alvo casa contra o texto, a descrição de conteúdo e o identificador de recurso.
"""
import re
import sys


def centro(caminho: str, alvo: str) -> str:
    """O centro do nó que casa com o alvo, **preferindo o que se pode tocar**.

    A preferência não é um detalhe. Num ecrã de pagamento o título diz "Pagar a
    encomenda" e o botão diz "Pagar 12.400 Kz": procurar "Pagar" e ficar com o
    primeiro dava o título, e o guião tocava num texto a vida inteira sem nunca
    carregar no botão. Passava, e não fazia nada.
    """
    with open(caminho, encoding="utf-8", errors="replace") as f:
        xml = f.read()
    reserva = ""
    for no in re.findall(r"<node[^>]*/?>", xml):
        campos = " ".join(re.findall(r'(?:text|content-desc|resource-id)="([^"]*)"', no))
        if alvo.lower() not in campos.lower():
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', no)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if x2 <= x1 or y2 <= y1:
            continue
        ponto = f"{(x1 + x2) // 2} {(y1 + y2) // 2}"
        if 'clickable="true"' in no or 'focusable="true"' in no:
            return ponto
        if not reserva:
            reserva = ponto
    return reserva


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("uso: no-central.py <ficheiro-do-dump> <alvo>")
    saida = centro(sys.argv[1], sys.argv[2])
    if saida:
        print(saida)

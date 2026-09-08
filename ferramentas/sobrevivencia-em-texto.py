#!/usr/bin/env python3
"""A resposta de `/v1/elementos/saude`, em texto legível.

Existe como ficheiro e não como uma linha dentro do guião porque a versão anterior
vivia num `python3 -c` dentro de um heredoc, e as aspas escapadas rebentavam com
`SyntaxError` **depois** de a medição estar feita: o número saía e o relatório não.
"""
import json
import sys


def principal() -> None:
    d = json.load(sys.stdin)["dados"]
    e = d["entre_versoes"]
    linhas = [
        ("de", e["de"]),
        ("para", e["para"]),
        ("identidades vistas na v1", e["vistos_na_versao_anterior"]),
        ("reconhecidas na v2", e["sobreviveram"]),
        ("identidades novas na v2", e["identidades_novas_na_versao_nova"]),
        ("taxa de sobrevivência", d["taxa_de_sobrevivencia"]),
        ("elementos no registo", d["elementos"]),
        ("observações", d["observacoes"]),
    ]
    for nome, valor in linhas:
        print(f"  {nome:<32}{valor}")
    print()
    print("  desfechos da reconciliação")
    for nome, valor in sorted(d["desfechos"].items()):
        print(f"    {nome:<30}{valor}")


if __name__ == "__main__":
    principal()

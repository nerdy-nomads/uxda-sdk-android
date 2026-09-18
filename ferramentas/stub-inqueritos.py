#!/usr/bin/env python3
"""O duplo do servidor para o ensaio do componente de inquérito no emulador. Cartão 14.1.

    python3 ferramentas/stub-inqueritos.py [porta]        (8791 por omissão)

**Não substitui o `ingest`**, que implementa o mesmo contrato a sério
(`docs/contrato-das-respostas.md`). Existe para o ensaio visual correr sem a base de dados,
o log e a consola de pé, e para se ver no terminal, byte a byte, o corpo que o telemóvel
envia: é aí que se confirma que o comentário chegou mascarado.

Responde as quatro rotas que a aplicação de ensaio usa, e só essas:

| Rota | O que devolve |
|---|---|
| `GET /v1/config` | a configuração de captura, com um bloco `inqueritos` de marca institucional |
| `POST /v1/respostas/elegibilidade` | `mostrar: true` com um `pedido_id` novo |
| `POST /v1/respostas` | `202`, e escreve o corpo inteiro na saída |
| `POST /v1/eventos`, `POST /v1/identidade/ligar` | `202`, com a contagem, sem escrever o conteúdo |

Recusa um comentário com um correio ou mais de quatro algarismos seguidos, como o servidor
real: um duplo mais permissivo do que o original escondia exatamente o defeito que o ensaio
existe para apanhar.

Só biblioteca padrão.
"""

import json
import re
import sys
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORTA = int(sys.argv[1]) if len(sys.argv) > 1 else 8791

# Uma marca institucional escura, e cantos arredondados: o ensaio tem de mostrar que o tema
# chega ao cartão, e o tema por omissão (azul sobre branco) não provava nada.
INQUERITOS = {
    "tema": {
        "cor_primaria": "#0b3d2e",
        "cor_fundo": "#f4f1ea",
        "cor_texto": "#10201a",
        "fonte": "Georgia, 'Times New Roman', serif",
        "cantos_px": 18,
        "idioma": "pt",
    },
    # Folgada de propósito, para o ensaio poder mostrar vários formatos seguidos. A regra de
    # uma pergunta por sessão continua a valer, e o guião limpa a aplicação entre formatos.
    "fadiga": {"max_pedidos": 20, "periodo_dias": 30, "excluir_respondeu_dias": 0},
    "associar_respostas": False,
    "lista": [
        {
            # Um gatilho a sério: dispara quando se toca no botão de pagar da loja.
            "chave": "facilidade_do_pagamento",
            "versao": 3,
            "formato": "esforco",
            "pergunta": {"pt": "Foi fácil pagar a encomenda?", "en": "Was it easy to pay for the order?"},
            "opcoes": [],
            "multipla": False,
            "comentario": True,
            "gatilho": "apos_conclusao",
            "criterios": [{"condicoes": [
                {"campo": "event_type", "operador": "igual", "valor": "toque"},
                {"campo": "element_key", "operador": "contem", "valor": "id=pagar"},
            ]}],
            "inicio": [{"condicoes": [{"campo": "screen_key", "operador": "igual", "valor": "/loja"}]}],
            "amostragem": 1,
            "atraso_ms": 1200,
            "contexto": {"tarefa": "pagar_uma_encomenda", "passo": "", "funcionalidade": "pagamento"},
        },
        {
            # Pedido pela aplicação, com `Uxda.inquerito`: a amostragem a zero prova que o
            # pedido explícito salta o sorteio.
            "chave": "porque_desistiu",
            "versao": 1,
            "formato": "escolha",
            "pergunta": {"pt": "O que mais atrapalhou?", "en": "What got in the way the most?"},
            "opcoes": [
                {"chave": "demorou", "pt": "Demorou muito", "en": "It took too long"},
                {"chave": "confuso", "pt": "Não percebi o formulário", "en": "The form was confusing"},
                {"chave": "erro", "pt": "Apareceu um erro", "en": "An error appeared"},
                {"chave": "nada", "pt": "Nada, correu bem", "en": "Nothing, it went well"},
            ],
            "multipla": True,
            "comentario": True,
            "gatilho": "amostragem",
            "criterios": [],
            "inicio": [],
            "amostragem": 0,
            "atraso_ms": 0,
            "contexto": {"tarefa": "pagar_uma_encomenda", "passo": "", "funcionalidade": ""},
        },
        {
            "chave": "recomendacao",
            "versao": 1,
            "formato": "recomendacao",
            "pergunta": {"pt": "Recomendaria esta loja a alguém?", "en": "Would you recommend this shop?"},
            "opcoes": [],
            "multipla": False,
            "comentario": False,
            "gatilho": "amostragem",
            "criterios": [],
            "inicio": [],
            "amostragem": 0,
            "atraso_ms": 0,
            "contexto": {"tarefa": "", "passo": "", "funcionalidade": ""},
        },
    ],
}

CONFIG = {
    "amostragem": 1,
    "nivel": "padrao",
    "amostragem_detalhado": 0,
    "rastreio_individual": False,
    "versao": 14,
    "inqueritos": INQUERITOS,
}

FORMATOS = {"esforco": (1, 7), "satisfacao": (1, 5), "recomendacao": (0, 10), "escolha": None, "livre": None}
contagem = {"eventos": 0, "lotes": 0}


def com_conteudo(texto: str) -> str:
    """A mesma regra do `TextoComConteudo` do servidor."""
    for parte in texto.split():
        i = parte.find("@")
        if i > 0 and "." in parte[i:]:
            return "texto com correio electrónico por mascarar"
    if re.search(r"\d{5}", texto):
        return "texto com uma sequência de dígitos por mascarar"
    return ""


class Duplo(BaseHTTPRequestHandler):
    server_version = "stub-inqueritos/1"

    def log_message(self, formato, *args):  # o registo por omissão é ruído
        pass

    def responder(self, estado: int, corpo: dict):
        dados = json.dumps(corpo, ensure_ascii=False).encode("utf-8")
        self.send_response(estado)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(dados)))
        self.end_headers()
        self.wfile.write(dados)

    def ler(self):
        n = int(self.headers.get("Content-Length") or 0)
        bruto = self.rfile.read(n).decode("utf-8") if n else ""
        try:
            return bruto, json.loads(bruto) if bruto else {}
        except json.JSONDecodeError:
            return bruto, None

    def do_GET(self):
        if self.path.startswith("/v1/config"):
            print(f"[config] chave={self.headers.get('X-UXDA-Key')}", flush=True)
            self.responder(200, {"sucesso": True, "dados": CONFIG})
            return
        self.responder(404, {"sucesso": False, "erro": "rota inexistente"})

    def do_POST(self):
        bruto, corpo = self.ler()
        if corpo is None:
            self.responder(400, {"sucesso": False, "erro": "json ilegível"})
            return
        if self.path.startswith("/v1/respostas/elegibilidade"):
            pedido = str(uuid.uuid4())
            print(f"[elegibilidade] {bruto}  ->  mostrar=true pedido_id={pedido}", flush=True)
            self.responder(200, {"sucesso": True, "dados": {"mostrar": True, "motivo": "pode", "pedido_id": pedido}})
            return
        if self.path.startswith("/v1/respostas"):
            print("[resposta] corpo recebido:", flush=True)
            print(json.dumps(corpo, ensure_ascii=False, indent=2), flush=True)
            inq = next((i for i in INQUERITOS["lista"] if i["chave"] == corpo.get("inquerito")), None)
            motivo = ""
            if inq is None or corpo.get("formato") != inq["formato"]:
                motivo = "inquérito ou formato desconhecido"
            elif FORMATOS[inq["formato"]] and not isinstance(corpo.get("nota"), int):
                motivo = "falta a nota"
            elif FORMATOS[inq["formato"]] is None and "nota" in corpo:
                motivo = "nota num formato sem escala"
            elif len(corpo.get("comentario", "")) > 500:
                motivo = "comentário longo de mais"
            else:
                motivo = com_conteudo(corpo.get("comentario", ""))
                if motivo:
                    motivo = "comentario_com_conteudo: " + motivo
            if motivo:
                print(f"[resposta] RECUSADA: {motivo}", flush=True)
                self.responder(400, {"sucesso": False, "erro": motivo})
                return
            print(f"[resposta] aceite: comentário mascarado no dispositivo = {corpo.get('comentario')!r}", flush=True)
            self.responder(202, {"sucesso": True, "dados": {"aceite": True, "associada": False}})
            return
        if self.path.startswith("/v1/eventos"):
            n = len(corpo.get("eventos", [])) if isinstance(corpo, dict) else 0
            contagem["eventos"] += n
            contagem["lotes"] += 1
            print(f"[eventos] lote de {n} (total {contagem['eventos']} em {contagem['lotes']} lotes)", flush=True)
            self.responder(202, {"sucesso": True, "dados": {"aceites": n}})
            return
        if self.path.startswith("/v1/identidade/ligar"):
            self.responder(202, {"sucesso": True, "dados": {}})
            return
        self.responder(404, {"sucesso": False, "erro": "rota inexistente"})


if __name__ == "__main__":
    servidor = ThreadingHTTPServer(("0.0.0.0", PORTA), Duplo)
    print(f"stub-inqueritos à escuta em 0.0.0.0:{PORTA} (do emulador: http://10.0.2.2:{PORTA})", flush=True)
    try:
        servidor.serve_forever()
    except KeyboardInterrupt:
        pass

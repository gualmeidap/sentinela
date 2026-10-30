#!/usr/bin/env python3
"""Abre o painel da instancia privada so na sua maquina, sem hospedar nada.

A pagina (web/) e estatica: so desenha o que a API devolve. Este script serve
essa pasta em 127.0.0.1 e, para tudo que a pagina pede em /api/..., faz a
chamada a API privada por ela, ja com a chave de leitura. Nada vai para a AWS e
nenhuma porta fica aberta para fora da maquina.

POR QUE UM PROXY E NAO A CHAVE DENTRO DA PAGINA. A API privada exige a chave de
leitura em cada GET. Se a chave fosse escrita no JavaScript, ela apareceria no
codigo-fonte da pagina e no DevTools. Aqui o navegador so conversa com
localhost; quem conhece a chave e este processo. Como a pagina e a chamada saem
da mesma origem, tambem some a dependencia de CORS.

O proxy so repassa GET para tres formas de caminho (/sistemas, e dentro deles
/disponibilidade e /eventos) e so aceita requisicao cujo Host seja o proprio
localhost: sem isso, uma pagina qualquer aberta no navegador poderia fazer o
navegador chamar este endereco e usar a chave sem que voce soubesse.

Uso (PowerShell):
    $env:SENTINELA_URL_PRIVADA = "https://<endereco-da-api-privada>"
    $env:SENTINELA_CHAVE_LEITURA = "<a chave de leitura>"
    python infra/ver_painel_privado.py
"""
import functools
import http.server
import os
import pathlib
import re
import shutil
import sys
import tempfile
import urllib.error
import urllib.request
import webbrowser

RAIZ = pathlib.Path(__file__).resolve().parent.parent
PASTA_WEB = RAIZ / "web"
PORTA = 5500
TEMPO_LIMITE_S = 15

ENDERECO_VALIDO = re.compile(r"^https://[A-Za-z0-9.-]+/?$")
# Letras, numeros e o que base64 e url-safe usam. Fora disso o valor iria para
# um cabecalho HTTP, onde quebra de linha vira injecao de outro cabecalho.
CHAVE_VALIDA = re.compile(r"^[A-Za-z0-9_+/=.-]{16,200}$")
CABECALHO_DA_CHAVE = "X-Chave-Leitura"
LINHA_DA_API = re.compile(r"^const API = '[^']*';$", re.MULTILINE)

PREFIXO_DO_PROXY = "/api"
# Primeiro caractere alfanumerico: "." e ".." nao sao ids, e seriam normalizados
# pela API para outro caminho.
CAMINHO_PERMITIDO = re.compile(
    r"^/api(/sistemas(/[A-Za-z0-9][A-Za-z0-9._-]{0,63}(/(disponibilidade|eventos))?)?)$"
)


def endereco_da_api():
    endereco = os.environ.get("SENTINELA_URL_PRIVADA", "").strip()
    if not endereco:
        sys.exit("defina SENTINELA_URL_PRIVADA com o endereco da API privada")
    if not ENDERECO_VALIDO.match(endereco):
        sys.exit("SENTINELA_URL_PRIVADA deve ser https://dominio, sem caminho nem aspas")
    return endereco.rstrip("/")


def chave_de_leitura():
    chave = os.environ.get("SENTINELA_CHAVE_LEITURA", "").strip()
    if not chave:
        sys.exit("defina SENTINELA_CHAVE_LEITURA com a chave de leitura da API privada")
    # A mensagem nao repete o valor: so diz que o formato nao serve.
    if not CHAVE_VALIDA.match(chave):
        sys.exit("SENTINELA_CHAVE_LEITURA com formato invalido")
    return chave


def destino_no_proxy(caminho, endereco):
    """Endereco da API para o caminho pedido a pagina, ou None se nao e permitido."""
    sem_consulta = caminho.split("?", 1)[0]
    if not CAMINHO_PERMITIDO.match(sem_consulta):
        return None
    return endereco + sem_consulta[len(PREFIXO_DO_PROXY):]


def preparar(destino):
    """Copia web/ e aponta a pagina para o proxy local (/api)."""
    shutil.copytree(PASTA_WEB, destino, dirs_exist_ok=True)
    painel = destino / "painel.js"
    texto = painel.read_text(encoding="utf-8")
    novo, trocas = LINHA_DA_API.subn(f"const API = '{PREFIXO_DO_PROXY}';", texto)
    if trocas != 1:
        # Se alguem reformatar a constante em web/painel.js, preferir parar
        # a servir uma pagina que ainda aponta para localhost:8080.
        sys.exit("nao achei a linha 'const API = ...;' em web/painel.js")
    painel.write_text(novo, encoding="utf-8")


class Manipulador(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, endereco, chave, **kwargs):
        self.endereco = endereco
        self.chave = chave
        super().__init__(*args, **kwargs)

    def do_GET(self):
        sem_consulta = self.path.split("?", 1)[0]
        if sem_consulta == PREFIXO_DO_PROXY or sem_consulta.startswith(PREFIXO_DO_PROXY + "/"):
            self.encaminhar()
        else:
            super().do_GET()

    def encaminhar(self):
        porta = self.server.server_address[1]
        if self.headers.get("Host") not in (f"localhost:{porta}", f"127.0.0.1:{porta}"):
            self.responder(403, b"")
            return
        alvo = destino_no_proxy(self.path, self.endereco)
        if alvo is None:
            self.responder(404, b"")
            return
        requisicao = urllib.request.Request(alvo, headers={CABECALHO_DA_CHAVE: self.chave})
        try:
            with urllib.request.urlopen(requisicao, timeout=TEMPO_LIMITE_S) as resposta:
                self.responder(resposta.status, resposta.read(), resposta.headers.get("Content-Type"))
        except urllib.error.HTTPError as erro:
            # 401, 404 e afins passam como vieram: a pagina mostra o status.
            self.responder(erro.code, erro.read(), erro.headers.get("Content-Type"))
        except OSError:
            self.responder(502, b"")

    def responder(self, status, corpo, tipo=None):
        self.send_response(status)
        self.send_header("Content-Type", tipo or "application/octet-stream")
        self.send_header("Content-Length", str(len(corpo)))
        # Dado privado nao deve ficar em cache do navegador.
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(corpo)


def main():
    endereco = endereco_da_api()
    chave = chave_de_leitura()
    with tempfile.TemporaryDirectory(prefix="sentinela-painel-") as pasta:
        destino = pathlib.Path(pasta)
        preparar(destino)

        manipulador = functools.partial(
            Manipulador, directory=str(destino), endereco=endereco, chave=chave
        )
        servidor = http.server.ThreadingHTTPServer(("127.0.0.1", PORTA), manipulador)
        url = f"http://localhost:{PORTA}"
        print(f"Painel em {url}  (Ctrl+C para encerrar)")
        webbrowser.open(url)
        try:
            servidor.serve_forever()
        except KeyboardInterrupt:
            print("\nencerrado")
        finally:
            servidor.server_close()


if __name__ == "__main__":
    main()

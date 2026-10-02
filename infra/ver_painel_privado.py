#!/usr/bin/env python3
"""Abre o painel da instancia privada so na sua maquina, sem hospedar nada.

A pagina (web/) e estatica: so desenha o que a API devolve. Este script copia
essa pasta para um diretorio temporario, troca o endereco da API pelo da
instancia privada e serve o resultado em 127.0.0.1. Nada vai para a AWS e
nenhuma porta fica aberta para fora da maquina.

O endereco da API privada entra por variavel de ambiente e nao fica em
arquivo nenhum do repositorio -- a copia que recebe o endereco vive e morre
em memoria temporaria, junto com o processo.

A API privada so libera CORS para http://localhost:5500 (parametro
OrigensPermitidas do deploy). Por isso a porta e fixa, e o endereco para abrir
no navegador e localhost, nao 127.0.0.1: para o navegador sao origens
diferentes, e a segunda seria recusada.

Uso (PowerShell):
    $env:SENTINELA_URL_PRIVADA = "https://<endereco-da-api-privada>"
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
import webbrowser

RAIZ = pathlib.Path(__file__).resolve().parent.parent
PASTA_WEB = RAIZ / "web"
PORTA = 5500
ENDERECO_VALIDO = re.compile(r"^https://[A-Za-z0-9.-]+/?$")
LINHA_DA_API = re.compile(r"^const API = '[^']*';$", re.MULTILINE)


def endereco_da_api():
    endereco = os.environ.get("SENTINELA_URL_PRIVADA", "").strip()
    if not endereco:
        sys.exit("defina SENTINELA_URL_PRIVADA com o endereco da API privada")
    # O valor vai para dentro de um literal JavaScript. A validacao estreita
    # nao e capricho: aspa ou barra invertida aqui virariam codigo na pagina.
    if not ENDERECO_VALIDO.match(endereco):
        sys.exit("SENTINELA_URL_PRIVADA deve ser https://dominio, sem caminho nem aspas")
    return endereco.rstrip("/")


def preparar(destino, endereco):
    shutil.copytree(PASTA_WEB, destino, dirs_exist_ok=True)
    painel = destino / "painel.js"
    texto = painel.read_text(encoding="utf-8")
    novo, trocas = LINHA_DA_API.subn(f"const API = '{endereco}';", texto)
    if trocas != 1:
        # Se alguem reformatar a constante em web/painel.js, preferir parar
        # a servir uma pagina que ainda aponta para localhost:8080.
        sys.exit("nao achei a linha 'const API = ...;' em web/painel.js")
    painel.write_text(novo, encoding="utf-8")


def main():
    endereco = endereco_da_api()
    with tempfile.TemporaryDirectory(prefix="sentinela-painel-") as pasta:
        destino = pathlib.Path(pasta)
        preparar(destino, endereco)

        manipulador = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(destino))
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

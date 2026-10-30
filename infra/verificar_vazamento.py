#!/usr/bin/env python3
"""Falha se um arquivo versionado carrega algo que so pertence a instancia privada.

O repositorio e publico e o README principal aponta para ele. Este script roda
no CI a cada push e olha os arquivos que o git rastreia -- o que ja esta no
.gitignore nao chega aqui, e o que escapou do .gitignore e pego aqui.

O QUE ESTE SCRIPT NAO SABE. Ele nao tem, e nao pode ter, a lista do que e
privado: escrever o nome de um sistema real neste arquivo seria vaza-lo no
proprio arquivo que o protege. Por isso ele procura FORMAS -- URL de Lambda que
nao e da instancia publica, IP de rede interna, id de conta AWS, chave, arquivo
de configuracao local. Os nomes reais (empresa, sistema, dominio, id da conta)
ficam em infra/termos-proibidos.local.txt: um termo por linha, gitignorado, lido
so quando existe. Rode este script na sua maquina antes do push; no CI esse
arquivo nao existe e so as formas valem.

O RELATORIO NAO REPETE O ACHADO. O log do CI de um repositorio publico e
publico: imprimir o valor encontrado transformaria o aviso de vazamento no
vazamento. Sai so categoria, arquivo e linha.

Sem dependencia externa, so biblioteca padrao.

Uso:
    python infra/verificar_vazamento.py
"""

import re
import subprocess
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
ARQUIVO_DE_TERMOS_LOCAIS = RAIZ / "infra" / "termos-proibidos.local.txt"

LIMITE_DE_TAMANHO = 5 * 1024 * 1024

# Arquivos que existem so na maquina de quem opera. O .gitignore ja os bloqueia;
# aqui e a segunda trava, para o dia em que um "git add -f" ou um .gitignore
# editado deixar um deles passar.
ARQUIVOS_PROIBIDOS = [
    re.compile(r"(^|/)CLAUDE(\.local)?\.md$"),
    re.compile(r"(^|/)application-(local|privado)\.[^/]+$"),
    re.compile(r"(^|/)\.env(\.[^/]+)?$"),
    re.compile(r"(^|/)[^/]+\.local\.(yml|yaml|properties|txt)$"),
    re.compile(r"(^|/)alvos(?!\.exemplo\.)[^/]*\.(yml|json|properties)$"),
    re.compile(r"^publicador-auditoria/(config|cursor)\.json$"),
    re.compile(r"^exportador-publico/(config|estado|status)\.json$"),
    re.compile(r"\.(pem|key|p12|pfx)$"),
]

# Ids de URL de Lambda que ja estao no README: sao a instancia PUBLICA, o
# endereco que se quer mostrar. Um endereco novo entra aqui de proposito, no
# mesmo commit que o publica -- e essa e a confirmacao de que e publico.
URLS_PUBLICAS = frozenset({
    "44nv32cezgzqgenpffiwenvwua0yfaed",
    "ajswegtqzqq75cxnsidkrk4jxy0qqzjt",
})

# Conta que NAO e a nossa: a da camada publica do Lambda Web Adapter, que
# aparece no ARN do infra/api.yaml. O id da nossa conta nunca entra aqui.
CONTAS_AWS_PUBLICAS = frozenset({"753240598075"})

URL_DE_LAMBDA = re.compile(r"\b([a-z0-9]+)\.lambda-url\.[a-z0-9-]+\.on\.aws\b")
CONTA_EM_ARN = re.compile(r"arn:aws[a-z-]*:[a-z0-9-]*:[a-z0-9-]*:(\d{12}):")
CONTA_EM_NOME = re.compile(r"(?<=[-_])(\d{12})(?!\d)")

PADROES = [
    # 10.x, 172.16-31.x, 192.168.x e 100.64-127.x (a faixa do Tailscale).
    ("ip_de_rede_interna", re.compile(
        r"(?<![\d.])(10\.\d{1,3}\.\d{1,3}\.\d{1,3}"
        r"|192\.168\.\d{1,3}\.\d{1,3}"
        r"|172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}"
        r"|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3})(?![\d])"
    )),
    ("nome_de_maquina_tailscale", re.compile(r"\.ts\.net\b")),
    ("chave_de_acesso_aws", re.compile(r"\b(AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("segredo_aws", re.compile(r"aws_secret_access_key\s*[=:]\s*\S{20,}", re.IGNORECASE)),
    ("token_do_github", re.compile(r"\b(gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{50,})\b")),
    ("chave_privada", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
]


def arquivo_proibido(caminho):
    return any(padrao.search(caminho) for padrao in ARQUIVOS_PROIBIDOS)


def e_texto(conteudo):
    return b"\0" not in conteudo[:8192]


def url_de_lambda_permitida(identificador):
    # Um endereco de exemplo na documentacao ("xxxx.lambda-url...") nao aponta
    # para nada. Qualquer outro id de Lambda e tratado como possivelmente privado.
    return identificador in URLS_PUBLICAS or set(identificador) == {"x"}


def categorias_da_linha(linha, termos_locais):
    achadas = []
    for categoria, padrao in PADROES:
        if padrao.search(linha):
            achadas.append(categoria)
    if any(not url_de_lambda_permitida(i) for i in URL_DE_LAMBDA.findall(linha)):
        achadas.append("url_de_lambda_fora_da_lista_publica")
    contas = CONTA_EM_ARN.findall(linha) + CONTA_EM_NOME.findall(linha)
    if any(conta not in CONTAS_AWS_PUBLICAS for conta in contas):
        achadas.append("id_de_conta_aws")
    minuscula = linha.casefold()
    if any(termo in minuscula for termo in termos_locais):
        achadas.append("termo_da_lista_local")
    return achadas


def achados_do_arquivo(caminho, texto, termos_locais=()):
    achados = []
    if arquivo_proibido(caminho):
        achados.append(("arquivo_que_nao_deve_ser_versionado", caminho, 0))
    for numero, linha in enumerate(texto.splitlines(), start=1):
        for categoria in categorias_da_linha(linha, termos_locais):
            achados.append((categoria, caminho, numero))
    return achados


def relatorio(achados):
    # So categoria, arquivo e linha: ver o aviso de "porque" no topo do arquivo.
    return [f"{categoria}: {caminho}" + (f":{linha}" if linha else "") for categoria, caminho, linha in achados]


def ler_termos_locais(caminho=ARQUIVO_DE_TERMOS_LOCAIS):
    if not caminho.exists():
        return ()
    linhas = caminho.read_text(encoding="utf-8").splitlines()
    return tuple(l.strip().casefold() for l in linhas if l.strip() and not l.lstrip().startswith("#"))


def arquivos_versionados():
    saida = subprocess.run(["git", "ls-files", "-z"], cwd=RAIZ, check=True, capture_output=True)
    return [c for c in saida.stdout.decode("utf-8").split("\0") if c]


def main():
    termos = ler_termos_locais()
    achados = []
    verificados = 0
    for caminho in arquivos_versionados():
        arquivo = RAIZ / caminho
        if not arquivo.is_file():
            continue
        if arquivo_proibido(caminho):
            achados.extend(achados_do_arquivo(caminho, "", termos))
            continue
        if arquivo.stat().st_size > LIMITE_DE_TAMANHO:
            continue
        conteudo = arquivo.read_bytes()
        if not e_texto(conteudo):
            continue
        verificados += 1
        achados.extend(achados_do_arquivo(caminho, conteudo.decode("utf-8", errors="replace"), termos))

    origem = f"{len(termos)} termo(s) da lista local" if termos else "so as formas (sem lista local, como no CI)"
    if achados:
        print(f"{len(achados)} achado(s) em {verificados} arquivo(s) -- {origem}:", file=sys.stderr)
        for linha in relatorio(achados):
            print(f"  {linha}", file=sys.stderr)
        sys.exit(1)
    print(f"{verificados} arquivo(s) verificados, nenhum achado -- {origem}")


if __name__ == "__main__":
    main()

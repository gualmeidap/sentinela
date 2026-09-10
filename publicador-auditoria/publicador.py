#!/usr/bin/env python3
"""Publica no Sentinela eventos que ja existem na base de auditoria de um
sistema, sem modificar esse sistema.

O sistema de origem nao ganha uma linha de codigo: este script abre o SQLite
dele em modo somente leitura, guarda num arquivo proprio ate onde ja leu, e
traduz cada linha nova para o modelo de evento do Sentinela.

DADO PESSOAL: o filtro nao acontece na hora de montar o payload, e sim na
consulta. Apenas as colunas de COLUNAS sao lidas. As demais -- que carregam
nome, UPN ou e-mail -- nunca chegam a entrar em memoria, nem para serem
descartadas depois. Acrescentar coluna aqui exige revisar o que ela carrega.

Sem dependencia externa, so biblioteca padrao: o script precisa poder ser
copiado para um servidor e rodar sem "pip install" nenhum.
"""

import json
import logging
import os
import sqlite3
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

# As unicas colunas que este script pode ler.
COLUNAS = ("id", "data", "atividade", "sucesso")

TEMPO_LIMITE_S = 10

log = logging.getLogger("publicador")


def validar_tabela(tabela):
    # O nome da tabela vem da configuracao e nao pode ir como parametro de
    # bind -- SQL nao aceita bind em nome de objeto. Sem esta validacao, uma
    # configuracao editada por engano viraria injecao de SQL.
    if not tabela.isidentifier():
        raise ValueError(f"nome de tabela invalido: {tabela!r}")
    return tabela


def abrir_somente_leitura(caminho):
    # mode=ro nao e disciplina de quem escreve a query: e o proprio SQLite
    # recusando qualquer escrita nesta conexao.
    conexao = sqlite3.connect(f"file:{caminho}?mode=ro", uri=True, timeout=TEMPO_LIMITE_S)
    conexao.row_factory = sqlite3.Row
    return conexao


def maior_id(conexao, tabela):
    consulta = f"SELECT COALESCE(MAX(id), 0) FROM {validar_tabela(tabela)}"
    (valor,) = conexao.execute(consulta).fetchone()
    return valor


def linhas_novas(conexao, tabela, ultimo_id):
    consulta = (
        f"SELECT {', '.join(COLUNAS)} FROM {validar_tabela(tabela)} "
        "WHERE id > ? ORDER BY id"
    )
    return conexao.execute(consulta, (ultimo_id,))


def primeira_regra(atividade, regras):
    for regra in regras:
        if all(trecho in atividade for trecho in regra["contem"]):
            return regra
    return None


def para_utc(data_local, fuso):
    # A coluna guarda "09/09/2026 07:22": dia/mes/ano, hora local, sem
    # segundos e sem fuso no proprio texto. Por isso o fuso vem da
    # configuracao -- nao ha como deduzir do valor.
    momento = datetime.strptime(data_local, "%d/%m/%Y %H:%M")
    em_utc = momento.replace(tzinfo=ZoneInfo(fuso)).astimezone(timezone.utc)
    return em_utc.isoformat(timespec="milliseconds")


def traduzir(linha, config):
    regra = primeira_regra(linha["atividade"], config["regras"])
    if regra is None:
        return None

    if linha["sucesso"] is None:
        # Linha anterior a existir a coluna. Deduzir o resultado do texto ja
        # produziu falso "falha" neste sistema antes -- ignorar e melhor que
        # publicar um numero errado.
        return None

    sucesso = bool(linha["sucesso"])
    evento = {
        "sistema": config["sistema"],
        "tipo": regra["tipo"],
        "resultado": "sucesso" if sucesso else "falha",
        "ocorridoEm": para_utc(linha["data"], config["fuso"]),
    }
    if not sucesso:
        evento["motivo"] = regra["motivo"]
    if config.get("ambiente"):
        evento["contexto"] = {"ambiente": config["ambiente"]}
    return evento


def publicar(url, chave, evento):
    requisicao = urllib.request.Request(
        f"{url.rstrip('/')}/eventos",
        data=json.dumps(evento).encode("utf-8"),
        headers={"Content-Type": "application/json", "X-Chave-Aplicacao": chave},
        method="POST",
    )
    with urllib.request.urlopen(requisicao, timeout=TEMPO_LIMITE_S) as resposta:
        if resposta.status != 201:
            raise RuntimeError(f"resposta inesperada: HTTP {resposta.status}")


def ler_cursor(caminho):
    if not caminho.exists():
        return None
    return json.loads(caminho.read_text(encoding="utf-8"))["ultimo_id"]


def gravar_cursor(caminho, ultimo_id):
    caminho.write_text(json.dumps({"ultimo_id": ultimo_id}), encoding="utf-8")


def carregar_config():
    padrao = Path(__file__).resolve().parent / "config.json"
    caminho = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else padrao
    config = json.loads(caminho.read_text(encoding="utf-8"))
    # Caminhos relativos resolvem contra o arquivo de configuracao, e nao
    # contra o diretorio de trabalho: sob cron o diretorio de trabalho nao e
    # o do script. Caminho absoluto passa intacto.
    config["arquivo_de_cursor"] = caminho.parent / config["arquivo_de_cursor"]
    config["banco"] = caminho.parent / config["banco"]
    return config


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    url = os.environ.get("SENTINELA_URL")
    chave = os.environ.get("SENTINELA_CHAVE")
    if not url or not chave:
        sys.exit("defina SENTINELA_URL e SENTINELA_CHAVE no ambiente")

    config = carregar_config()
    cursor = config["arquivo_de_cursor"]
    conexao = abrir_somente_leitura(config["banco"])

    try:
        ultimo_id = ler_cursor(cursor)
        if ultimo_id is None:
            # Primeira execucao: marca o presente como ponto de partida em vez
            # de publicar o historico. As linhas antigas tem "sucesso" em
            # branco, e o painel so tem valor com o resultado confiavel.
            inicio = maior_id(conexao, config["tabela"])
            gravar_cursor(cursor, inicio)
            log.info("primeira execucao: partindo do id %s, historico nao e publicado", inicio)
            return

        enviados = ignorados = 0
        for linha in linhas_novas(conexao, config["tabela"], ultimo_id):
            evento = traduzir(linha, config)
            if evento is not None:
                try:
                    publicar(url, chave, evento)
                except (urllib.error.URLError, RuntimeError) as erro:
                    # Para no ponto em que falhou, sem avancar o cursor: a
                    # proxima execucao retoma daqui. O sistema de origem nao
                    # sabe nem se importa que isto aconteceu.
                    log.warning("parando no id %s: %s", linha["id"], erro)
                    break
                enviados += 1
            else:
                ignorados += 1
            gravar_cursor(cursor, linha["id"])

        log.info("%s evento(s) publicado(s), %s linha(s) ignorada(s)", enviados, ignorados)
    finally:
        conexao.close()


if __name__ == "__main__":
    main()

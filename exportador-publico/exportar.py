#!/usr/bin/env python3
"""Gera o status publico do portfolio a partir da Sentinela privada.

Le o que a API privada ja devolve (GET /sistemas/{id}/eventos), guarda o
resumo de cada dia num estado local e escreve um status.json que pode ir para
um repositorio publico.

O QUE FAZ ISTO SER SEGURO. A saida nunca e copiada do que a API devolve: ela e
montada campo a campo em construir_status(), a partir de uma lista fechada
(CAMPOS_PUBLICOS). O que nao foi escolhido nao tem caminho ate a saida --
id interno do sistema, nome de camera, motivo de falha, tipo de evento, nada
disso passa. test_exportar.py falha se um campo novo aparecer.

DIA FECHADO. So sai dia que ja terminou. O dia de hoje fica no estado local e
so e publicado amanha. Status em tempo real de um sistema domestico diz a um
atacante quando ele esta sem monitoramento; um numero por dia, publicado com
atraso, nao diz.

HONESTIDADE DO NUMERO. "sucesso" e a taxa de verificacoes que passaram entre as
que o proprio sistema reportou -- nao e uptime. Dia sem nenhuma verificacao e
lacuna: nao vira 100% e nao entra na conta.

Sem dependencia externa, so biblioteca padrao.

Uso (PowerShell):
    $env:SENTINELA_URL_PRIVADA = "https://<endereco-da-api-privada>"
    $env:SENTINELA_CHAVE_LEITURA = "<a chave de leitura, se a API exige>"
    python exportador-publico/exportar.py
"""

import json
import os
import re
import sys
import tempfile
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

VERSAO = 1
JANELA_DIAS = 30
TEMPO_LIMITE_S = 10

SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
DIA = re.compile(r"^\d{4}-\d{2}-\d{2}$")
ENDERECO_VALIDO = re.compile(r"^https://[A-Za-z0-9.-]+/?$")
# Letras, numeros e o que base64 e url-safe usam. Fora disso o valor iria para
# um cabecalho HTTP, onde quebra de linha vira injecao de outro cabecalho.
CHAVE_VALIDA = re.compile(r"^[A-Za-z0-9_+/=.-]{16,200}$")
CABECALHO_DA_CHAVE = "X-Chave-Leitura"

# Todo campo que pode existir no arquivo publico, por nivel. Se um campo novo
# for necessario, ele entra aqui de proposito, junto com o teste.
CAMPOS_PUBLICOS = {
    "raiz": frozenset({"versao", "geradoEm", "projetos"}),
    "projeto": frozenset({"slug", "nome", "sucesso30d", "dias"}),
    "dia": frozenset({"dia", "sucessoPct"}),
}


def endereco_da_api():
    endereco = os.environ.get("SENTINELA_URL_PRIVADA", "").strip()
    if not endereco:
        sys.exit("defina SENTINELA_URL_PRIVADA com o endereco da API privada")
    if not ENDERECO_VALIDO.match(endereco):
        sys.exit("SENTINELA_URL_PRIVADA deve ser https://dominio, sem caminho nem aspas")
    return endereco.rstrip("/")


def chave_de_leitura():
    """A chave que a API privada exige para leitura, ou None se nao ha uma.

    Sem a variavel o exportador ainda funciona contra uma API de leitura aberta.
    O valor so vai para o cabecalho: nunca para saida, log ou mensagem de erro,
    inclusive quando o formato e recusado.
    """
    chave = os.environ.get("SENTINELA_CHAVE_LEITURA", "").strip()
    if not chave:
        return None
    if not CHAVE_VALIDA.match(chave):
        sys.exit("SENTINELA_CHAVE_LEITURA com formato invalido")
    return chave


def validar_projeto(projeto, slugs_vistos):
    if not isinstance(projeto, dict):
        raise ValueError("cada projeto da configuracao deve ser um objeto")
    sistema_id, slug, nome = projeto.get("id"), projeto.get("slug"), projeto.get("nome")
    if not isinstance(sistema_id, str) or not sistema_id.strip():
        raise ValueError("projeto sem id")
    if not isinstance(slug, str) or not SLUG.match(slug):
        raise ValueError("slug deve ser minusculo, com letras, numeros e hifen")
    if slug in slugs_vistos:
        raise ValueError(f"slug repetido: {slug}")
    if not isinstance(nome, str) or not nome.strip() or len(nome) > 80 or not nome.isprintable():
        raise ValueError(f"nome publico invalido em {slug}")
    slugs_vistos.add(slug)


def carregar_config(caminho):
    config = json.loads(caminho.read_text(encoding="utf-8"))
    projetos = config.get("projetos")
    if not isinstance(projetos, list) or not projetos:
        raise ValueError("a configuracao precisa de ao menos um projeto")
    vistos = set()
    for projeto in projetos:
        validar_projeto(projeto, vistos)
    config["estado"] = caminho.parent / config.get("estado", "estado.json")
    config["saida"] = caminho.parent / config.get("saida", "status.json")
    return config


def resumo_da_resposta(corpo):
    """Extrai da resposta da API so tres campos, validados.

    A resposta vem de fora deste processo e tem muito mais do que isto (motivos,
    contexto, ultimos eventos). Aqui so atravessam dia, total e sucessos.
    """
    hoje = corpo["hoje"]
    dia, total, sucessos = hoje["dia"], hoje["total"], hoje["sucessos"]
    if not isinstance(dia, str) or not DIA.match(dia):
        raise ValueError("dia invalido")
    date.fromisoformat(dia)
    for valor in (total, sucessos):
        if isinstance(valor, bool) or not isinstance(valor, int):
            raise ValueError("contagem invalida")
    if not 0 <= sucessos <= total:
        raise ValueError("contagem incoerente")
    return {"dia": dia, "total": total, "sucessos": sucessos}


def registrar(estado, sistema_id, resumo):
    if resumo["total"] == 0:
        return False
    estado.setdefault(sistema_id, {})[resumo["dia"]] = {
        "total": resumo["total"],
        "sucessos": resumo["sucessos"],
    }
    return True


def podar(estado, hoje):
    inicio = date.fromisoformat(hoje) - timedelta(days=JANELA_DIAS)
    for registros in estado.values():
        for dia in [d for d in registros if date.fromisoformat(d) < inicio]:
            del registros[dia]


def percentual(sucessos, total):
    return round(100 * sucessos / total, 1)


def construir_status(projetos, estado, hoje, agora):
    fim = date.fromisoformat(hoje)
    inicio = fim - timedelta(days=JANELA_DIAS)

    publicados = []
    for projeto in projetos:
        registros = estado.get(projeto["id"], {})
        fechados = sorted(d for d in registros if inicio <= date.fromisoformat(d) < fim)
        total = sum(registros[d]["total"] for d in fechados)
        sucessos = sum(registros[d]["sucessos"] for d in fechados)
        publicados.append({
            "slug": projeto["slug"],
            "nome": projeto["nome"],
            "sucesso30d": percentual(sucessos, total) if total else None,
            "dias": [
                {"dia": d, "sucessoPct": percentual(registros[d]["sucessos"], registros[d]["total"])}
                for d in fechados
            ],
        })

    return {
        "versao": VERSAO,
        "geradoEm": agora.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "projetos": publicados,
    }


def buscar_resumo(api, sistema_id, chave=None):
    url = f"{api}/sistemas/{urllib.parse.quote(sistema_id, safe='')}/eventos"
    cabecalhos = {CABECALHO_DA_CHAVE: chave} if chave else {}
    requisicao = urllib.request.Request(url, headers=cabecalhos)
    with urllib.request.urlopen(requisicao, timeout=TEMPO_LIMITE_S) as resposta:
        return resumo_da_resposta(json.load(resposta))


def ler_estado(caminho):
    if not caminho.exists():
        return {}
    return json.loads(caminho.read_text(encoding="utf-8"))


def gravar_json(caminho, dados):
    caminho.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        "w", encoding="utf-8", dir=caminho.parent, suffix=".tmp", delete=False
    ) as temporario:
        json.dump(dados, temporario, ensure_ascii=False, indent=2)
        temporario.write("\n")
    os.replace(temporario.name, caminho)


def main():
    argumento = sys.argv[1] if len(sys.argv) > 1 else None
    caminho = Path(argumento).resolve() if argumento else Path(__file__).resolve().parent / "config.json"
    config = carregar_config(caminho)
    api = endereco_da_api()
    chave = chave_de_leitura()
    estado = ler_estado(config["estado"])

    hoje = None
    for projeto in config["projetos"]:
        try:
            resumo = buscar_resumo(api, projeto["id"], chave)
        except (OSError, ValueError, KeyError, TypeError) as erro:
            # O slug e publico; o id interno nao vai para a saida de erro.
            print(f"aviso: sem leitura de {projeto['slug']} ({type(erro).__name__})", file=sys.stderr)
            continue
        registrar(estado, projeto["id"], resumo)
        hoje = resumo["dia"]

    if hoje is None:
        # Nao reescrever o arquivo: geradoEm velho e a forma honesta de o site
        # mostrar que a coleta parou.
        sys.exit("nenhum projeto lido; o ultimo status publicado fica como esta")

    podar(estado, hoje)
    gravar_json(config["estado"], estado)
    status = construir_status(config["projetos"], estado, hoje, datetime.now(timezone.utc))
    gravar_json(config["saida"], status)

    dias = sum(len(p["dias"]) for p in status["projetos"])
    print(f"{len(status['projetos'])} projeto(s), {dias} dia(s) fechado(s) em {config['saida'].name}")


if __name__ == "__main__":
    main()

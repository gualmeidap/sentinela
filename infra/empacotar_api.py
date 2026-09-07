#!/usr/bin/env python3
"""Empacota a API para deploy na Lambda: gera o run.sh e zipa junto do jar.

O AWS Lambda Web Adapter exige um arquivo executavel como "Handler". O run.sh
so precisa dar "java -jar" no jar de sempre -- nada no codigo Spring muda.

Por que este script, e nao o comando "zip" direto: no Windows, o utilitario
"zip" comum nao grava o bit de execucao Unix dentro do arquivo, e sem esse bit
a Lambda recusa rodar o run.sh (erro de permissao no cold start). O modulo
zipfile do Python permite gravar esse bit manualmente via ZipInfo.external_attr,
testado e confirmado antes de este script existir.
"""
import pathlib
import stat
import sys
import zipfile

RAIZ = pathlib.Path(__file__).resolve().parent.parent
ALVO_DO_JAR = RAIZ / "api" / "target"
SAIDA = RAIZ / "infra" / "build" / "sentinela-api.zip"

RUN_SH = "#!/bin/sh\nexec java -jar {nome_do_jar}\n"


def encontrar_jar():
    candidatos = sorted(ALVO_DO_JAR.glob("sentinela-api-*.jar"))
    # O plugin do Spring Boot deixa dois arquivos: o jar executavel de verdade
    # e um "*.jar.original" (o jar simples, sem as dependencias, que o
    # glob acima ja exclui por nao terminar em ".jar").
    if not candidatos:
        sys.exit(f"nenhum jar encontrado em {ALVO_DO_JAR} -- rode 'mvn package' na pasta api/ antes")
    if len(candidatos) > 1:
        sys.exit(f"mais de um jar encontrado, ambiguo: {candidatos}")
    return candidatos[0]


def empacotar():
    jar = encontrar_jar()
    SAIDA.parent.mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(SAIDA, "w", zipfile.ZIP_DEFLATED) as zip_saida:
        info_run_sh = zipfile.ZipInfo("run.sh")
        info_run_sh.external_attr = (stat.S_IFREG | 0o755) << 16
        zip_saida.writestr(info_run_sh, RUN_SH.format(nome_do_jar=jar.name))

        zip_saida.write(jar, jar.name)

    print(f"empacotado: {SAIDA}  ({SAIDA.stat().st_size / 1024 / 1024:.1f} MB)")
    print(f"  run.sh -> exec java -jar {jar.name}")


if __name__ == "__main__":
    empacotar()

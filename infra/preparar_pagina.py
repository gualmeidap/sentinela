#!/usr/bin/env python3
"""Copia web/ para dentro do modulo pagina/, trocando o endereco da API.

web/ continua sendo a unica fonte de verdade da pagina -- ela nunca ganha
build nem framework, por decisao ja registrada no README. O que este script
faz e preparar uma COPIA, dentro de pagina/src/main/resources, para o Maven
empacotar junto do handler. A copia nunca deve ser editada a mao: rodar este
script de novo sempre a sobrescreve.

Sem argumento, so copia os arquivos como estao -- e o que o CI faz, para
testar o handler sem precisar saber nenhum endereco real. Com o endereco da
API como argumento, troca a linha "const API = ..." de painel.js antes de
copiar -- e o que o deploy de verdade faz.
"""
import pathlib
import re
import shutil
import sys

RAIZ = pathlib.Path(__file__).resolve().parent.parent
ORIGEM = RAIZ / "web"
DESTINO = RAIZ / "pagina" / "src" / "main" / "resources"

PADRAO_DA_LINHA = re.compile(r"^const API = '[^']*';$", re.MULTILINE)


def preparar(url_da_api: str | None):
    DESTINO.mkdir(parents=True, exist_ok=True)

    for nome in ("index.html", "estilo.css"):
        shutil.copyfile(ORIGEM / nome, DESTINO / nome)

    painel_js = (ORIGEM / "painel.js").read_text(encoding="utf-8")

    if url_da_api:
        if not PADRAO_DA_LINHA.search(painel_js):
            # Falha alto e explicito: se painel.js mudar de formato e este
            # script parar de achar a linha, o pior desfecho seria publicar a
            # pagina apontando pro localhost de sempre, em silencio.
            sys.exit("nao encontrei a linha 'const API = ...' em web/painel.js -- "
                     "o script precisa ser atualizado junto com o arquivo")
        painel_js = PADRAO_DA_LINHA.sub(f"const API = '{url_da_api}';", painel_js)

    (DESTINO / "painel.js").write_text(painel_js, encoding="utf-8")

    print(f"preparado em {DESTINO}")
    print(f"  API = {url_da_api or '(inalterado, aponta para o localhost do repositorio)'}")


if __name__ == "__main__":
    preparar(sys.argv[1] if len(sys.argv) > 1 else None)

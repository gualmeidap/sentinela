# Como um sistema publica eventos no Sentinela

Guia genérico para qualquer sistema que vá chamar `POST /eventos`. Nenhum
identificador real de nenhuma instância aparece aqui — troque `<algo>` pelos
valores da sua aplicação.

## O contrato

```
POST https://<endereco-da-api>/eventos
Content-Type: application/json
X-Chave-Aplicacao: <chave combinada com o Sentinela>

{
  "sistema": "<id da sua aplicacao>",
  "tipo": "<codigo da lista fechada>",
  "resultado": "sucesso" | "falha",
  "motivo": "<codigo da lista fechada, so em falha>",
  "ocorridoEm": "<instante ISO-8601 em UTC>",
  "contexto": { "<chave>": "<codigo curto>" }
}
```

- `sistema` precisa bater exatamente com o `id` configurado do lado do
  Sentinela — é o que autentica que aquela chave pode falar por aquele
  sistema.
- `tipo` e `motivo` são **lista fechada**: o que não estiver cadastrado do
  lado do Sentinela é recusado com `400`. Adicionar um código novo é mudar a
  configuração do Sentinela, não o código dele.
- `motivo` só existe quando `resultado` é `"falha"`. Nunca é mensagem de
  exceção crua — é sempre um código curto (`dependencia_indisponivel`, não
  `"Connection refused to 10.0.4.12:389"`).
- `contexto` é opcional, e cada valor precisa ser um código curto em
  minúsculas (`^[a-z0-9][a-z0-9_.-]{0,39}$`). Nunca nome, e-mail ou qualquer
  coisa que identifique uma pessoa — essa é a regra inegociável do projeto.
- `ocorridoEm` aceita tanto `2026-09-10T00:59:37.214Z` quanto
  `2026-09-10T00:59:37.214+00:00` — os dois testados e aceitos.

Respostas: `201` sem corpo quando aceito. Em erro, um
[ProblemDetail](https://www.rfc-editor.org/rfc/rfc7807) com um campo `codigo`
estável — ver a tabela no `README.md` principal.

## Exemplo em Python

```python
import os
import logging
from datetime import datetime, timezone

import requests

log = logging.getLogger(__name__)

SENTINELA_URL = os.environ["SENTINELA_URL"]          # ex.: https://xxxx.lambda-url.us-east-1.on.aws
SENTINELA_CHAVE = os.environ["SENTINELA_CHAVE"]       # nunca no codigo, sempre variavel de ambiente
SENTINELA_SISTEMA = "<id da sua aplicacao>"
TIMEOUT_SEGUNDOS = 2


def publicar_evento(tipo: str, sucesso: bool, motivo: str | None = None,
                     contexto: dict[str, str] | None = None) -> None:
    """Avisa o Sentinela sobre um evento de negocio. Nunca lanca excecao.

    O Sentinela e observabilidade, nao o sistema principal: se ele estiver
    fora do ar ou lento, isso nao pode atrasar nem derrubar o fluxo real do
    usuario. Por isso o timeout e curto e toda falha e so registrada em log,
    nunca propagada.
    """
    corpo = {
        "sistema": SENTINELA_SISTEMA,
        "tipo": tipo,
        "resultado": "sucesso" if sucesso else "falha",
        "ocorridoEm": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
    }
    if motivo:
        corpo["motivo"] = motivo
    if contexto:
        corpo["contexto"] = contexto

    try:
        resposta = requests.post(
            f"{SENTINELA_URL}/eventos",
            json=corpo,
            headers={"X-Chave-Aplicacao": SENTINELA_CHAVE},
            timeout=TIMEOUT_SEGUNDOS,
        )
        if not resposta.ok:
            # Loga o codigo do Sentinela, nunca o corpo -- o corpo de uma
            # recusa nao deve ser ecoado sem revisar o que ele repete de volta.
            log.warning("Sentinela recusou o evento: HTTP %s", resposta.status_code)
    except requests.RequestException as erro:
        log.warning("Sentinela indisponivel, evento nao publicado: %s", erro)


# Uso, logo apos a redefinicao de senha terminar:
#
#   publicar_evento("senha.redefinida", sucesso=True)
#   publicar_evento("senha.redefinida", sucesso=False, motivo="dependencia_indisponivel")
```

### Se o meio de milissegundo de latência importar

`requests.post` é síncrono: a chamada espera a resposta do Sentinela antes de
continuar. Com timeout de 2s isso raramente pesa, mas se o fluxo real for
sensível a isso, rode em uma thread solta e não espere o resultado:

```python
import threading

def publicar_evento_async(*args, **kwargs):
    threading.Thread(target=publicar_evento, args=args, kwargs=kwargs, daemon=True).start()
```

`daemon=True` é o que garante que essa thread nunca segura o processo
principal de encerrar por causa dela.

## Antes de ligar de verdade

- [ ] O `id` usado em `sistema` está cadastrado do lado do Sentinela, com a
      mesma chave.
- [ ] Todo `tipo` e `motivo` que o publicador pretende enviar já está na lista
      fechada da configuração do Sentinela — divergência aqui é `400` silencioso
      por evento, não um erro visível de uma vez.
- [ ] `SENTINELA_CHAVE` vem de variável de ambiente ou cofre de segredos,
      nunca commitada.
- [ ] A chamada nunca pode derrubar nem atrasar de forma perceptível o fluxo
      real do usuário.

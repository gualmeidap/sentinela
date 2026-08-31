# Sentinela

Portal que mostra, numa tela so, o estado dos sistemas em producao. Faz duas
coisas ao mesmo tempo:

1. **Disponibilidade** — cada sistema esta de pe, e respondendo em quanto tempo.
2. **Eventos de negocio** — o que esses sistemas fizeram: quantas redefinicoes
   de senha deram certo hoje, quantas falharam e por que.

A segunda parte e o diferencial. UptimeRobot, Loki e CloudWatch resolvem a
primeira, mas nenhum deles entende o que e uma "redefinicao de senha" — esse
significado so existe dentro dos sistemas monitorados.

Nenhum dado pessoal atravessa: o portal responde "o que, onde, quando e com
que resultado", nunca "com quem".

## Pecas

| Pasta | O que faz | Estado |
|---|---|---|
| `api/` | Recebe eventos publicados pelos sistemas, le historico e expoe REST (Spring Boot) | esqueleto no ar |
| `coletor/` | Verifica cada sistema a cada 5 minutos e grava o resultado (Java puro, Lambda) | nao iniciado |
| `web/` | Pagina com o painel (HTML/JS estatico, S3 + CloudFront) | nao iniciado |
| — | Persistencia (DynamoDB) | nao iniciado |

As pecas nao se chamam entre si: o banco e o unico ponto de encontro.

O detalhamento de escopo, modelo de evento e decisoes esta em [CLAUDE.md](CLAUDE.md).

## Rodar a API local

Requer apenas Java 21. O Maven vem junto no repositorio (Maven Wrapper),
entao nao e preciso instalar Maven.

```
cd api
./mvnw spring-boot:run
```

No PowerShell, use `.\mvnw.cmd spring-boot:run`.

A aplicacao sobe em http://localhost:8080.

```
curl http://localhost:8080/ping
```

## Configuracao de alvos

A lista de sistemas monitorados **nunca** fica no repositorio. Ela vem de
configuracao externa, fornecida por instancia. Os padroes de arquivo de
configuracao local estao no `.gitignore`.

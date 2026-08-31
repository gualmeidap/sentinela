# Sentinela — briefing

> Os nomes de sistemas neste documento são genéricos por decisão de projeto.
> A instância privada monitora sistemas reais cujos identificadores não são
> versionados — ver "Duas instâncias".

## O que é

Portal que mostra, numa tela só, o estado dos sistemas que eu mantenho em
produção. Duas coisas ao mesmo tempo:

1. **Disponibilidade** — cada sistema está de pé? respondendo em quanto tempo?
2. **Eventos de negócio** — o que esses sistemas fizeram? quantas redefinições
   de senha deram certo hoje, quantas falharam e por quê?

A segunda parte é o diferencial. Ferramentas prontas (UptimeRobot, Grafana
Loki, CloudWatch) resolvem a primeira, mas nenhuma entende o que é uma
"redefinição de senha" ou uma "nota processada" — esse significado só existe
dentro dos meus sistemas.

Projeto pessoal, com dois propósitos: ter o painel de fato, e servir de
portfólio para vagas de back-end Java.

## Arquitetura

Um repositório, quatro peças. As peças não se chamam entre si — o banco é o
único ponto de encontro.

| Peça | O que faz | Tecnologia |
|---|---|---|
| `coletor/` | A cada 5 min chama cada sistema e grava se respondeu e em quanto tempo | Java puro (sem Spring), AWS Lambda + EventBridge |
| `api/` | Recebe eventos publicados pelos sistemas; lê histórico e eventos; expõe REST | Spring Boot em EC2 |
| `web/` | Página com o painel | HTML/JS estático em S3 + CloudFront |
| — | Persistência | DynamoDB |

**Por que DynamoDB:** está no Always Free da AWS, e o portal só tem valor se
ficar ligado indefinidamente. As consultas são simples (por sistema, por
intervalo de tempo), então não preciso de relacional.

**Por que Java puro no Lambda:** Spring Boot tem cold start de vários segundos,
o que não compensa numa função que roda a cada 5 minutos. A API, que é
long-running, fica em Spring Boot.

## Modelo de evento

Cada sistema publica em `POST /eventos`, autenticado por chave própria da
aplicação.

```json
{
  "sistema": "sistema-publicador",
  "tipo": "senha.redefinida",
  "resultado": "falha",
  "motivo": "dependencia_indisponivel",
  "ocorridoEm": "2026-08-30T14:20:11Z"
}
```

Regras do modelo:

- `tipo` e `motivo` são **códigos de uma lista fechada**, nunca texto livre e
  nunca mensagem de exceção crua. Mensagem crua mais cedo ou mais tarde
  carrega nome de usuário ou caminho interno junto, e quebra o agrupamento.
- `resultado` é `sucesso` ou `falha`. `motivo` só aparece em falha.
- Campos de contexto adicionais são permitidos desde que não identifiquem
  pessoa (ex.: `campus`, `fornecedorTipo`).

### Dado pessoal — restrição inegociável

**Nenhum dado pessoal atravessa.** Não há nome, matrícula, CPF, e-mail ou
identificador de usuário no evento. Não se trata de mascarar na exibição: o
dado nunca sai do sistema de origem.

Quem precisa saber *quem* foi consulta o log de auditoria do sistema de
origem, que já existe e é o lugar certo para isso.

O Sentinela responde "o quê, onde, quando e com que resultado" — não "com
quem".

## Escopo da versão 1

Fechado. Alerta, login, gráfico histórico e métrica agregada de longo prazo
ficam para depois.

**Disponibilidade**
- Sistema monitorado tem nome e URL. A lista vem de configuração externa,
  nunca do repositório.
- Verificação a cada 5 minutos: horário, respondeu ou não, tempo de resposta.
- Na tela: indicador verde/vermelho, tempo de resposta atual, disponibilidade
  das últimas 24h em fita de blocos de 15 minutos.

**Eventos**
- Endpoint `POST /eventos` com chave por aplicação.
- Um único publicador na v1: **sistema-publicador**, escolhido por ter volume
  diário suficiente para validar a tela (dezenas de eventos/dia, contra ~1 por
  dia nos demais candidatos).
- Na tela, por sistema: contagem do dia agrupada por tipo e resultado
  ("47 sucesso, 3 falha"), e lista dos últimos eventos com horário, tipo,
  resultado e motivo.
- Falha agrupada por motivo — "3 falhas, todas pela mesma dependência
  indisponível" é a informação útil; "3 falhas" não é.

**Contenção de volume**
- TTL no DynamoDB: registros expiram sozinhos (30 dias como ponto de partida).
- Limite de escrita por aplicação: se um sistema disparar volume anormal, o
  portal para de aceitar daquela chave e registra o silenciamento.

## Duas instâncias

Mesmo código, dois ambientes.

A **privada** monitora e recebe eventos dos sistemas reais. Fica atrás de
autenticação.

A **pública**, que é a demo do portfólio, monitora alvos fictícios e recebe
eventos sintéticos. É a única que aparece no README, em captura de tela ou em
link.

Nada da instância privada — URL interna, nome de sistema, IP, chave — pode
aparecer em código, configuração versionada, README ou imagem.

## Ordem de execução

Cada etapa funcionando antes da próxima. Nada sobe para a AWS antes de rodar
local.

1. Spring Boot local, banco local, lista de sistemas fixa no código.
   Endpoints de disponibilidade respondendo.
2. Página simples lendo da API local.
3. `POST /eventos` funcionando local, com eventos enviados na mão via curl.
4. Troca do banco local para DynamoDB.
5. Coletor em Java puro, local primeiro, depois em Lambda com EventBridge.
6. Deploy: API na EC2, página no S3 com CloudFront.
7. Só então: alterar o sistema publicador para publicar eventos de verdade.

## Exigências de qualidade

Isto faz parte do escopo, não é extra. É o que separa um projeto de portfólio
que impressiona de um que não.

- **Teste automatizado** cobrindo as regras de negócio: agregação por motivo,
  cálculo de disponibilidade, rejeição de evento malformado, limite por
  aplicação.
- **Tratamento de erro explícito**: alvo que não responde, alvo que demora
  demais, DynamoDB que recusa escrita, Lambda que estoura o tempo. Cada um
  com comportamento definido, não exceção vazando.
- **CI no GitHub Actions** rodando os testes a cada push, com badge no README.
- **README explicando as decisões**, não só como instalar: por que DynamoDB e
  não Postgres, por que Java puro no Lambda, por que evento agregado em vez de
  log corrido.
- **Commits pequenos e frequentes**, ao longo do tempo.

## Contexto sobre mim

Trabalho com Python e Flask/FastAPI. **Nunca usei Spring Boot nem AWS.**
Explique as decisões enquanto construímos — o objetivo é aprender, não só ter
o projeto pronto.

## Custo

Conta AWS no plano gratuito, com crédito limitado. Configurar alerta no AWS
Budgets antes de subir qualquer coisa. Evitar NAT Gateway, que cobra por hora
só de existir. Preferir sempre o que estiver no Always Free.

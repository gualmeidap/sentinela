# Sentinela

![Java](https://img.shields.io/badge/Java-21-b07219)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F)
![AWS](https://img.shields.io/badge/AWS-Always%20Free-FF9900)
![Status](https://img.shields.io/badge/status-em%20desenvolvimento-blue)

Portal que mostra, numa tela só, o estado dos sistemas que mantenho em produção
— e o que esses sistemas fizeram.

Projeto pessoal com dois propósitos: ter o painel de fato, e servir de portfólio
para vagas de back-end Java.

## O problema

Monitoramento comum responde uma pergunta: *o sistema está de pé?* O Sentinela
responde duas.

1. **Disponibilidade** — cada sistema está respondendo, e em quanto tempo.
2. **Eventos de negócio** — o que cada sistema fez. Quantas redefinições de
   senha deram certo hoje, quantas falharam, e por quê.

A segunda é o diferencial. UptimeRobot, Grafana Loki e CloudWatch resolvem a
primeira muito bem, mas nenhum deles sabe o que é uma "redefinição de senha" ou
uma "nota processada" — esse significado só existe dentro dos sistemas
monitorados.

Saber que o sistema devolveu `200` em 180 ms não conta que as redefinições de
senha estão falhando há duas horas porque uma dependência saiu do ar. O painel
verde e o usuário travado convivem sem se contradizer, e é justamente esse vão
que o Sentinela cobre.

## Dado pessoal não atravessa

Restrição inegociável: **nenhum dado pessoal entra no Sentinela.** Sem nome,
matrícula, CPF, e-mail ou identificador de usuário.

Não se trata de mascarar na exibição — o dado nunca sai do sistema de origem.
Quem precisa saber *quem* fez consulta o log de auditoria do sistema de origem,
que já existe e é o lugar certo para isso.

O Sentinela responde **o quê, onde, quando e com que resultado**. Nunca "com quem".

## Arquitetura

```mermaid
flowchart LR
    S["Sistemas monitorados"]
    C["coletor<br/>Java puro · Lambda + EventBridge"]
    A["api<br/>Spring Boot · EC2"]
    DB[("DynamoDB<br/>TTL de 30 dias")]
    W["web<br/>HTML/JS · S3 + CloudFront"]

    C -->|"verifica a cada 5 min"| S
    C -->|"grava disponibilidade"| DB
    S -->|"POST /eventos"| A
    A -->|"grava evento / lê histórico"| DB
    W -->|"GET (REST)"| A
```

| Peça | O que faz | Tecnologia | Estado |
|---|---|---|---|
| `coletor/` | A cada 5 min chama cada sistema e grava se respondeu e em quanto tempo | Java puro, Lambda + EventBridge | não iniciado |
| `api/` | Recebe eventos publicados pelos sistemas, lê histórico e expõe REST | Spring Boot em EC2 | disponibilidade no ar (local) |
| `web/` | Página com o painel | HTML/JS estático, S3 + CloudFront | painel lendo a API local |
| — | Persistência | DynamoDB | não iniciado |

**As peças não se chamam entre si.** O banco é o único ponto de encontro: o
coletor escreve, a API lê. Uma peça fora do ar não derruba a outra, e cada uma
sobe, cai e escala sozinha — o que também torna possível rodar só a API na
máquina local, sem coletor nenhum, que é como o projeto começou.

### Estrutura do repositório

```
sentinela/
├── api/                      Spring Boot — única peça com código hoje
│   ├── mvnw, mvnw.cmd        Maven Wrapper: build sem instalar Maven
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/sentinela/api/
│       │   ├── sistema/          catálogo dos alvos (lista fixa, passo 1)
│       │   ├── disponibilidade/  regra de cálculo, persistência e endpoints
│       │   ├── erro/             tradução de exceção em resposta HTTP
│       │   ├── dev/              semeador de dados sintéticos (perfil local)
│       │   └── ping/
│       └── test/java/com/sentinela/api/
├── coletor/                  reservado — passo 5
└── web/                      painel estático, sem build
    ├── index.html
    ├── estilo.css
    └── painel.js
```

## Decisões

### Por que DynamoDB e não Postgres

O portal só tem valor se ficar ligado indefinidamente, e o RDS não está no
Always Free da AWS — uma instância gerenciada cobra por hora, ociosa ou não. O
DynamoDB está, com 25 GB e capacidade suficiente para esta carga.

O custo dessa escolha seria consulta complexa, mas ela não aparece aqui: tudo
que o painel pergunta é "os registros do sistema X neste intervalo de tempo",
que é exatamente o acesso por chave de partição mais faixa de ordenação para o
qual o DynamoDB foi feito. Sem junção, sem relatório ad-hoc, sem agregação de
longo prazo na v1. Escolher relacional seria pagar em custo fixo por
flexibilidade que o escopo não usa.

### Por que Java puro no Lambda e Spring Boot na API

São perfis de execução opostos.

O coletor roda a cada 5 minutos, faz algumas chamadas HTTP e morre. O cold start
do Spring Boot — vários segundos subindo contexto e varrendo classes — seria
gasto em toda invocação, para inicializar uma infraestrutura que a função nem
usa. Java puro sobe em milissegundos.

A API é long-running: sobe uma vez e fica. O custo de inicialização é pago uma
vez só, e em troca vêm injeção de dependência, serialização, validação e
tratamento de erro prontos. Aqui o Spring paga por si.

### Por que evento com código fechado, e não log corrido

`tipo` e `motivo` são códigos de uma lista fechada — nunca texto livre, nunca
mensagem de exceção crua.

Mensagem crua mais cedo ou mais tarde carrega junto um nome de usuário ou um
caminho interno, e aí o dado pessoal entra pela porta dos fundos sem ninguém ter
decidido isso. Além do risco, ela quebra o agrupamento: `"Connection refused to
servidor-a"` e `"Connection refused to servidor-b"` são duas strings diferentes
e uma única causa. Com código fechado, três falhas pelo mesmo motivo aparecem como
"3 falhas, todas pela mesma dependência indisponível" — que é a informação útil.
"3 falhas" não é.

### Por que a página não tem framework

O painel é HTML, CSS e JavaScript puros. Não é purismo: o destino dele é ser
arquivo estático servido pelo CloudFront, e um framework acrescentaria um passo
de build entre escrever e publicar — mais uma coisa para quebrar, versionar e
manter, em troca de conveniência que uma tela com três cartões não precisa.

A regra de negócio também não está lá. Fita, percentual e estado de bloco são
calculados na API; a página só desenha o que recebe. Se o cálculo vivesse no
JavaScript, ele teria de ser reescrito e testado de novo em qualquer outro
consumidor — e sairia do alcance dos testes automatizados.

### Por que o banco é o único ponto de encontro

Coletor e API nunca se chamam. Se o coletor tivesse que avisar a API a cada
verificação, uma API fora do ar viraria buraco no histórico, e a Lambda passaria
a precisar de rota de rede até a EC2 — que na AWS é onde aparece o NAT Gateway,
cobrado por hora só de existir. Escrevendo direto no DynamoDB, o coletor não
depende de ninguém e o custo continua no Always Free.

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

- `tipo` e `motivo` vêm de lista fechada.
- `resultado` é `sucesso` ou `falha`. `motivo` só aparece em falha.
- Campos de contexto adicionais são permitidos desde que não identifiquem
  pessoa (ex.: `campus`, `fornecedorTipo`).

## Endpoints

| Método | Rota | O que faz | Estado |
|---|---|---|---|
| `GET` | `/ping` | Verificação de vida da própria API | no ar |
| `GET` | `/sistemas` | Lista os sistemas monitorados com o estado de cada um agora | no ar |
| `GET` | `/sistemas/{id}/disponibilidade` | Fita das últimas 24 h em blocos de 15 min, com o percentual | no ar |
| `POST` | `/eventos` | Recebe evento de negócio, com chave por aplicação | passo 3 |

Id desconhecido devolve `404` no formato ProblemDetail (RFC 7807), com um campo
`codigo` estável para o cliente decidir o que fazer sem depender do texto.

As rotas de leitura de eventos entram junto com o passo 3.

## Escopo da versão 1

Fechado. Alerta, login, gráfico histórico e métrica agregada de longo prazo
ficam para depois.

**Disponibilidade**
- Sistema monitorado tem nome e URL. A lista vem de configuração externa, nunca
  do repositório.
- Verificação a cada 5 minutos: horário, respondeu ou não, tempo de resposta.
- Na tela: indicador verde/vermelho, tempo de resposta atual e disponibilidade
  das últimas 24 h em fita de blocos de 15 minutos.

**Eventos**
- `POST /eventos` com chave por aplicação.
- Um único publicador na v1, escolhido por ter volume diário suficiente para
  validar a tela (dezenas de eventos/dia, contra ~1 por dia nos demais
  candidatos).
- Na tela, por sistema: contagem do dia agrupada por tipo e resultado
  ("47 sucesso, 3 falha") e lista dos últimos eventos com horário, tipo,
  resultado e motivo. Falha sempre agrupada por motivo.

**Contenção de volume**
- TTL no DynamoDB: registros expiram sozinhos, 30 dias como ponto de partida.
- Limite de escrita por aplicação: se um sistema disparar volume anormal, o
  portal para de aceitar daquela chave e registra o silenciamento.

## Duas instâncias

Mesmo código, dois ambientes.

A **privada** monitora e recebe eventos dos sistemas reais, atrás de
autenticação. A **pública** é a demo deste repositório: monitora alvos
fictícios e recebe eventos sintéticos.

Nada da instância privada — URL interna, nome de sistema, IP ou chave — aparece
em código, configuração versionada, README ou imagem. É por isso que os
identificadores neste documento são genéricos, e por isso que a lista de alvos
vem sempre de configuração externa.

## Como rodar

Requer apenas **Java 21**. O Maven vem junto no repositório (Maven Wrapper),
então não é preciso instalar Maven.

```bash
cd api && ./mvnw spring-boot:run
```

No PowerShell, use `.\mvnw.cmd spring-boot:run`. A aplicação sobe em
`http://localhost:8080`.

```bash
curl http://localhost:8080/sistemas
```

### O painel

Sirva a pasta `web/` em qualquer servidor estático. A porta 5500 é a que já vem
autorizada no CORS da API:

```bash
python -m http.server 5500 --directory web --bind 127.0.0.1
```

E abra `http://127.0.0.1:5500`.

A API só entrega resposta às origens declaradas em `sentinela.origens-permitidas`,
no `application.yml`. Se você servir a página em outra porta, acrescente a origem
lá — senão o navegador recusa a resposta e a página exibe o aviso de conexão.

No perfil `local`, que é o padrão, a aplicação semeia 24 h de verificações
sintéticas na subida — o coletor que gera medição de verdade só existe no passo
5. Sem isso os endpoints responderiam corretamente uma tela vazia, e não daria
para conferir se a fita e o percentual estão certos. Fora do perfil `local` nada
é semeado: dado sintético misturado com medição real seria pior do que dado
nenhum.

Para rodar os testes:

```bash
cd api && ./mvnw test
```

## Estado atual

Cada etapa funcionando antes da próxima. Nada sobe para a AWS antes de rodar local.

- [x] **1.** Spring Boot local, banco local, lista de sistemas fixa no código,
      endpoints de disponibilidade respondendo
- [x] **2.** Página simples lendo da API local
- [ ] **3.** `POST /eventos` funcionando local, eventos enviados na mão via curl
- [ ] **4.** Troca do banco local para DynamoDB
- [ ] **5.** Coletor em Java puro, local primeiro, depois em Lambda com EventBridge
- [ ] **6.** Deploy: API na EC2, página no S3 com CloudFront
- [ ] **7.** Publicação de eventos reais pelo sistema de origem

Em paralelo, como parte do escopo e não como extra: teste automatizado das
regras de negócio (agregação por motivo, cálculo de disponibilidade, rejeição de
evento malformado, limite por aplicação), tratamento explícito de cada modo de
falha — alvo que não responde, alvo lento demais, banco recusando escrita,
Lambda estourando o tempo — e CI no GitHub Actions rodando os testes a cada push.

## Custo

Conta AWS no plano gratuito, com crédito limitado. Alerta no AWS Budgets antes
de subir qualquer coisa. Sem NAT Gateway, que cobra por hora só de existir.
Preferência sempre pelo que estiver no Always Free — foi o critério que decidiu
o DynamoDB.

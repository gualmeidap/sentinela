# Sentinela

[![CI](https://github.com/gualmeidap/sentinela/actions/workflows/ci.yml/badge.svg)](https://github.com/gualmeidap/sentinela/actions/workflows/ci.yml)
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
    A["api<br/>Spring Boot · Lambda"]
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
| `coletor/` | A cada 5 min chama cada sistema e grava se respondeu e em quanto tempo | Java puro, Lambda + EventBridge | **no ar na AWS** |
| `api/` | Recebe eventos publicados pelos sistemas, lê histórico e expõe REST | Spring Boot em Lambda (Function URL) | **no ar na AWS** |
| `web/` | Página com o painel | HTML/JS estático, S3 + CloudFront | no ar (local) |
| — | Persistência | DynamoDB | **no ar na AWS** |

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
├── coletor/                  Java puro, sem Spring
│   ├── mvnw, mvnw.cmd
│   ├── pom.xml
│   ├── alvos.exemplo.properties
│   └── src/main/java/com/sentinela/coletor/
│       └── lambda/           ponto de entrada quando roda como Lambda
├── docker-compose.yml        DynamoDB Local para desenvolver
├── infra/                    CloudFormation: o que sobe para a AWS
│   ├── README.md             custo estimado, decisões e como remover tudo
│   ├── tabelas.yaml
│   ├── coletor.yaml
│   ├── api.yaml               Lambda da API atrás de uma Function URL
│   ├── empacotar_api.py       gera o run.sh e zipa com o jar, sem mudar o build
│   └── implantar.sh
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

A API é long-running: sobe uma vez e fica — pelo menos na EC2 do plano
original. Em troca da inicialização mais cara vêm injeção de dependência,
serialização, validação e tratamento de erro prontos. Aqui o Spring paga por
si.

### Por que a API também virou Lambda, e não ficou em EC2

O plano original era EC2, e mudou depois de revisar com calma: EC2 só é
gratuita nos **12 meses da conta**, contados da criação — não do uso. Esta
conta já existe há mais tempo que isso. Confirmar a data exata exigiria mais
uma permissão de IAM só para essa checagem, e o objetivo declarado é nunca
pagar nada — não valeu o risco de apostar errado.

A solução: a mesma arquitetura Always Free que já vale para o coletor. O
código Spring Boot **não mudou uma linha**. Quem torna isso possível é o
[AWS Lambda Web Adapter](https://github.com/awslabs/aws-lambda-web-adapter),
mantido pela AWS — uma extensão que roda ao lado da aplicação, recebe cada
invocação da Lambda e encaminha como uma requisição HTTP comum para
`localhost:8080`, a mesma porta de sempre. A aplicação não sabe que está numa
Lambda; o único arquivo novo é um `run.sh` de duas linhas que dá `java -jar`
no jar que já existia.

O preço dessa escolha, para ser honesto: diferente de uma EC2 sempre ligada,
a Lambda "esfria" depois de um tempo ociosa. Medido de verdade na AWS, o
Spring Boot leva **8,2 segundos** para subir; como isso passa dos ~9,8s de
inicialização com CPU extra que a Lambda oferece, `AWS_LWA_ASYNC_INIT` entrou
em ação como previsto — sem reiniciar a função, só empurrando o resto da
espera para a primeira invocação (**10,6s cobrados nela**). Chamadas
seguintes, com a função já quente, respondem em poucos milissegundos —
confirmado com uma sequência real: 15 ms, depois 126 ms, depois 4 ms. Trocar
dinheiro incerto por alguns segundos ocasionais de espera foi a troca certa
aqui.

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

### Por que a lista de tipos e motivos vive na configuração

`tipo` e `motivo` são lista fechada, mas a lista mora no `application.yml`, por
aplicação — não num `enum` Java. Acrescentar um código novo passa a ser mudança
de configuração em vez de recompilação e deploy, sem deixar de ser fechado: o
que não está declarado é recusado com `400`.

O mesmo bloco de configuração declara quem pode publicar, com que chave, com que
códigos e com que volume. Manter as quatro coisas juntas evita o caso clássico
de alguém cadastrar uma chave nova e esquecer do limite.

### Por que valor de contexto só aceita código

O modelo permite campos de contexto extras (`campus`, `fornecedorTipo`) desde que
não identifiquem pessoa. Só que "não identifica pessoa" não se verifica sozinho —
então a regra virou formato: chave declarada na configuração, e valor obrigado a
casar com `^[a-z0-9][a-z0-9_.-]{0,39}$`.

`unidade_2` passa. `Joao da Silva` e `joao@exemplo.com` não. É uma barreira
estrutural, não um pedido de boa vontade ao publicador.

### Como as tabelas do DynamoDB foram modeladas

Em banco relacional você modela os dados e depois escreve qualquer consulta. No
DynamoDB é o contrário: **a tabela é desenhada em função das consultas**, porque
o que a chave não atende exige varrer a tabela inteira — que é o que fica lento
e caro conforme o histórico cresce.

Toda pergunta do painel tem a forma *"do sistema X, entre tal e tal hora"*, e as
chaves saem direto disso:

| | Verificações | Eventos |
|---|---|---|
| Partição | `sistemaId` | `sistema` |
| Ordenação | `momento` | `momento#identificador` |

Nenhuma consulta precisa de varredura. A fita de 24 h é uma faixa de ordenação;
a última verificação é a mesma partição em ordem invertida com limite 1.

**São duas tabelas, e não uma.** Existe a técnica de *single-table design*, que
junta tudo numa tabela só; ela vale quando uma única consulta precisa trazer
entidades de tipos diferentes de uma vez. Aqui disponibilidade e eventos são
sempre consultados separadamente, então ela cobraria complexidade sem entregar
nada.

**As chaves de ordenação são diferentes de propósito.** Na verificação, a chave
é só o momento: se o coletor rodar duas vezes no mesmo instante, a segunda
escrita substitui a primeira, e a gravação vira idempotente sem uma linha de
código — o que importa quando o coletor for uma Lambda, que pode ser invocada
duas vezes para o mesmo disparo. No evento, a chave carrega um identificador
junto: dois eventos podem acontecer no mesmo milissegundo, e ali sobrescrever
seria perder dado sem nenhum erro aparecer.

**Capacidade provisionada, não sob demanda.** O Always Free cobre 25 unidades de
leitura e 25 de escrita provisionadas; o modo sob demanda é cobrado por
requisição. Com 5 de cada por tabela sobra folga larga e o total fica bem abaixo
do limite gratuito.

**TTL nativo:** cada item carrega a data em que deve expirar, e o DynamoDB apaga
sozinho — sem rotina de limpeza para escrever nem manter.

### Quantas dependências o coletor carrega, e por quê

Até o passo 4 o coletor declarava **zero** dependências de execução: cliente
HTTP, leitura de configuração e concorrência já vêm no JDK 21. O jar tinha 17 KB.

Para gravar no DynamoDB isso não se sustenta. O protocolo exige assinatura
SigV4, e implementar criptografia à mão para economizar biblioteca seria uma
troca ruim. O SDK entrou, e o jar foi para **6,9 MB** — cerca de **700 ms** a
mais na partida, que numa função invocada a cada 5 minutos é custo permanente.

O que dá para fazer é cortar o supérfluo. O SDK traz por padrão dois clientes
HTTP (Netty e Apache), ambos servidores de rede completos; a Lambda faz poucas
chamadas síncronas, então ambos foram excluídos em favor do cliente simples do
JDK.

Os alvos continuam sendo verificados em paralelo com **threads virtuais** do
Java 21. A espera é de rede, não de processamento; em série, dez alvos custariam
a soma de dez esperas — e a Lambda cobra por tempo de execução.

### Por que o coletor duplica código da API

A classe `Verificacao` e o formato das chaves são quase iguais aos do `api/`, e a
duplicação é deliberada. Um jar compartilhado entre as duas peças viraria, com o
tempo, um caminho de acoplamento: uma mudança no formato de leitura da API
poderia quebrar o coletor rodando em produção sem ninguém ter tocado nele.

O preço dessa escolha é real e vale nomear: **o único contrato entre as peças é
o formato do item no banco**, e ele pode divergir sem nada quebrar visivelmente —
o coletor grava, a API não acha, e o painel fica vazio sem uma linha de erro.

Por isso o formato literal da chave (`2026-09-01T10:00:00.000Z`) está fixado em
teste dos **dois** lados, e há um teste na API que lê um item escrito no formato
exato do coletor.

Esse teste não é hipotético: quando as peças se encontraram pela primeira vez, a
API respondeu `500`. O coletor grava o tempo decorrido também nas falhas — 5000 ms
de tempo esgotado é informação diferente de 30 ms de conexão recusada — e o
modelo da API recusa "não respondeu com tempo de resposta". As duas estavam
certas isoladamente e incompatíveis juntas. A conciliação ficou na leitura: o
tempo só é lido quando o alvo respondeu, porque "quanto se esperou até desistir"
não é tempo de resposta, e mostrar `5000 ms` ao lado de um indicador vermelho
faria a tela mentir.

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

Coletor e API nunca se chamam — nem quando os dois viraram Lambda. Se o
coletor tivesse que avisar a API a cada verificação, uma API fora do ar (ou
fria, ainda inicializando) viraria buraco no histórico, e as duas Lambdas
ficariam acopladas por disponibilidade uma da outra, exatamente o problema que
"não se chamam entre si" existe para evitar. Escrevendo direto no DynamoDB, o
coletor não depende de ninguém, e nenhuma VPC ou NAT Gateway — que cobra por
hora só de existir — precisa entrar na conta.

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
| `POST` | `/eventos` | Recebe evento de negócio, autenticado por chave da aplicação | no ar |
| `GET` | `/sistemas/{id}/eventos` | Contagem do dia por tipo e resultado, falhas por motivo, e os últimos eventos | no ar |

Todo erro sai no formato ProblemDetail (RFC 7807), com um campo `codigo` estável
para o cliente decidir o que fazer sem depender do texto:

| Situação | Status | `codigo` |
|---|---|---|
| Chave ausente ou desconhecida | `401` | `chave_invalida` |
| Chave publicando por outro sistema | `403` | `publicacao_nao_autorizada` |
| Tipo ou motivo fora da lista fechada | `400` | `tipo_desconhecido`, `motivo_desconhecido` |
| Campo que a API não conhece no payload | `400` | `corpo_ilegivel` |
| Volume acima do combinado | `429` | `limite_excedido` |

**Nenhuma resposta de erro devolve conteúdo da requisição.** Num portal cuja regra
é que dado pessoal não atravessa, a mensagem de erro é justamente por onde ele
passaria sem ninguém perceber.

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

### O coletor

Requer apenas Java 21. A lista de alvos **nunca** vem do repositório: copie
`coletor/alvos.exemplo.properties` para `alvos.properties` (bloqueado no
`.gitignore`) e edite.

```bash
cd coletor && ./mvnw -q package && java -jar target/sentinela-coletor-0.0.1-SNAPSHOT.jar alvos.properties
```

Saída de uma rodada:

```
verificando 3 alvo(s), tempo limite de 5000 ms
  22:24:32  alvo-fora-do-ar         FORA         91 ms  CONEXAO_RECUSADA
  22:24:32  sentinela-api           no ar       112 ms  HTTP 200
  22:24:32  sentinela-painel        no ar       113 ms  HTTP 200
  3 alvo(s) verificado(s), 1 fora do ar
```

Na Lambda não há disco para arquivo de configuração, então a lista vem da
variável de ambiente `SENTINELA_ALVOS`, no formato `id=url;id=url`. O tempo
limite sai de `SENTINELA_TIMEOUT_MS` (padrão 5000).

Por enquanto o coletor só imprime o resultado: ele e a API se encontram no banco,
e o banco compartilhado só existe a partir do passo 4.

### Com DynamoDB em vez de memória

Sobe uma cópia do DynamoDB na sua máquina — sem conta AWS e sem custo:

```bash
docker compose up -d
```

E a API apontando para ela:

```bash
cd api && SPRING_PROFILES_ACTIVE=local,dynamo SENTINELA_DYNAMO_ENDPOINT=http://localhost:8000 SENTINELA_DYNAMO_CRIAR_TABELAS=true ./mvnw spring-boot:run
```

Sem o perfil `dynamo`, valem os repositórios em memória e o SDK da AWS nem entra
no contexto — dá para rodar a aplicação e a suíte inteira sem banco nenhum por
perto. Os testes de integração são pulados quando o DynamoDB Local não está no
ar; no CI ele sobe pelo mesmo `docker-compose.yml`, então lá eles sempre rodam.

### O coletor gravando no banco

Com o DynamoDB Local no ar, o coletor deixa de só imprimir:

```bash
cd coletor && SENTINELA_DYNAMO_TABELA=sentinela-verificacoes SENTINELA_DYNAMO_ENDPOINT=http://localhost:8000 java -jar target/sentinela-coletor-0.0.1-SNAPSHOT.jar alvos.properties
```

Sem a variável `SENTINELA_DYNAMO_TABELA` ele apenas imprime, como antes. Com
ela, faz as duas coisas — e na Lambda o que vai para a saída padrão cai no
CloudWatch Logs, então imprimir continua útil.

Use no `alvos.properties` os mesmos ids do catálogo da API, senão o painel não
tem onde mostrar o que foi medido.

### Na AWS

A pasta [`infra/`](infra/) descreve, em CloudFormation, o que sobe: as duas
tabelas, as duas funções Lambda (coletor e API), os papéis IAM, o agendamento
de 5 em 5 minutos, o endereço público da API (Function URL) e os grupos de log
com retenção fixada.

**Estado real, ambiente `publico`:** tabelas, coletor e API já rodam na conta
AWS. A API responde em `https://44nv32cezgzqgenpffiwenvwua0yfaed.lambda-url.us-east-1.on.aws/`
— por exemplo, `.../sistemas` devolve o estado atual dos três alvos, lido do
DynamoDB de verdade. Falta só a página (S3/CloudFront) para existir um link
único, com painel, para abrir no navegador. Ver [`infra/README.md`](infra/README.md)
para o porquê de cada decisão, o custo estimado e o procedimento para remover
tudo.

**Custo esperado: US$ 0/mês, para sempre** — nada aqui depende da idade da
conta. O uso fica uma ou duas ordens de grandeza abaixo de cada limite do
nível gratuito.

O script `infra/implantar.sh` mostra cada comando e pede confirmação antes de
executar. A lista de alvos não fica em nenhum arquivo versionado: é digitada na
hora do deploy, porque numa instância privada ela carrega URL interna.

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
- [x] **3.** `POST /eventos` funcionando local, eventos enviados na mão via curl
- [x] **4.** Troca do banco local para DynamoDB
- [x] **5.** Coletor em Java puro, local primeiro, depois em Lambda com
      EventBridge — verifica em paralelo, classifica cada modo de falha, grava
      no DynamoDB e **roda de verdade na AWS**, a cada 5 minutos, sozinho
- [ ] **6.** Deploy: API em Lambda (Function URL), página no S3 com CloudFront
      — *API **no ar na AWS**, lendo e gravando no DynamoDB de verdade; falta
      só a página (S3/CloudFront) para existir um link único e navegável*
- [ ] **7.** Publicação de eventos reais pelo sistema de origem

Como parte do escopo e não como extra, já de pé: **112 testes** (79 na API, 33
no coletor) cobrindo cálculo de disponibilidade, agregação de evento por
motivo, limite de escrita por aplicação, rejeição de payload malformado, os
quatro modos de falha do coletor, a regra de CORS e a leitura/escrita real
contra DynamoDB Local; e **CI no GitHub Actions** rodando tudo a cada push, em
dois jobs paralelos com o próprio DynamoDB Local subindo no runner Linux — que
é o que pega o que só quebra fora do Windows.

Tratamento explícito de erro cobrindo cada modo previsto no escopo: alvo que
não responde (`TEMPO_ESGOTADO`), alvo lento demais (mesmo código, tempo
decorrido registrado), alvo com porta fechada ou host inexistente
(`CONEXAO_RECUSADA`), alvo respondendo com erro (`STATUS_DE_ERRO`), e chave,
tipo, motivo ou volume inválidos em `POST /eventos`, cada um com seu próprio
código de resposta.

## Custo

Conta AWS no plano gratuito. Alerta no AWS Budgets configurado antes de subir
qualquer recurso — inclusive um "zero-spend budget", que avisa no primeiro
centavo. Sem NAT Gateway, que cobra por hora só de existir; sem EC2, que só é
gratuita nos 12 meses da conta, não do uso. Preferência sempre pelo que
estiver no Always Free — foi o critério que decidiu o DynamoDB, e depois a
Lambda para as duas peças de código. Contas detalhadas em
[`infra/README.md`](infra/README.md).

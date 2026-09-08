# Infraestrutura

O que sobe para a AWS, quanto custa e como remover.

**Estado em 08/09/2026, ambiente `publico`:** as três pilhas rodam de verdade
na conta AWS — tabelas, coletor e API, confirmado com invocação manual e
leitura do DynamoDB pelas duas funções. A API pública responde em
`https://44nv32cezgzqgenpffiwenvwua0yfaed.lambda-url.us-east-1.on.aws/`.
Falta só a página (S3/CloudFront) para existir um link único e navegável.

## Antes de qualquer coisa: o alerta de gastos

O maior risco deste projeto não é uma conta alta — é uma conta pequena que
ninguém percebe por seis meses. Configure o **AWS Budgets** antes de criar o
primeiro recurso:

1. Console da AWS → **Billing and Cost Management** → **Budgets**
2. *Create budget* → **Zero spend budget** (avisa no primeiro centavo)
3. E um segundo, de custo mensal com limite baixo (US$ 1, por exemplo)

Isso é feito por você, no console. Não faz parte destes arquivos.

## O que cada pilha cria

| Arquivo | Cria | Por que separado |
|---|---|---|
| `tabelas.yaml` | As duas tabelas do DynamoDB | Os dados sobrevivem à recriação do coletor e da API |
| `coletor.yaml` | Lambda, papel IAM, grupo de log e o agendamento de 5 em 5 min | Pode ser derrubado e recriado sem perder histórico |
| `api.yaml` | Lambda da API, papel IAM, grupo de log e o endereço público (Function URL) | Mesmo motivo: reimplantar a API não deve apagar dado nenhum |

As pilhas de coletor e API **dependem** da de tabelas: importam os nomes e
ARNs de lá.

## Por que a API também é Lambda, e não EC2

O briefing original previa a API numa instância EC2. Mudou depois de revisar
com o Gustavo: **EC2 só é gratuita nos primeiros 12 meses da conta**, contados
da criação da conta — não do uso. Essa conta AWS já existe há mais tempo que
isso, e confirmar a data exata exigiria mais uma permissão de leitura só para
essa checagem. Dado que o objetivo declarado é nunca pagar nada, não valeu a
pena arriscar.

A solução: a mesma arquitetura Always Free que já valida o coletor. O
código Spring Boot **não mudou uma linha** — quem torna isso possível é o
[AWS Lambda Web Adapter](https://github.com/awslabs/aws-lambda-web-adapter),
mantido pela AWS. É uma extensão que roda ao lado da aplicação: recebe cada
invocação da Lambda, encaminha como uma requisição HTTP comum para
`http://localhost:8080` (a mesma porta de sempre), e devolve a resposta. A
aplicação não sabe que está numa Lambda.

O único arquivo novo é um `run.sh` de duas linhas que dá `java -jar` no mesmo
jar que já roda local. Ver `infra/empacotar_api.py`.

**Function URL, não API Gateway.** API Gateway nunca teve Always Free — só um
nível gratuito de 12 meses, hoje descontinuado para contas novas. Function URL
não é um serviço separado: é só um endereço HTTPS direto para a Lambda,
cobrado como a própria invocação — ou seja, dentro do Always Free de novo.

## Custo estimado

Com 3 alvos verificados a cada 5 minutos e uso ocasional do painel:

| Serviço | Uso mensal | Nível gratuito | Custo |
|---|---|---|---|
| Lambda (coletor) — invocações | ~8.640 | 1.000.000 | US$ 0 |
| Lambda (coletor) — computação | ~4.300 GB-s | 400.000 GB-s | US$ 0 |
| Lambda (API) — invocações | algumas centenas a poucos milhares | 1.000.000 | US$ 0 |
| Lambda (API) — computação | folga larga mesmo em 1 GB de memória | 400.000 GB-s | US$ 0 |
| DynamoDB — capacidade | 10 de leitura, 10 de escrita | 25 e 25 | US$ 0 |
| DynamoDB — armazenamento | poucos MB | 25 GB | US$ 0 |
| EventBridge — regra agendada | 8.640 disparos | ilimitado | US$ 0 |
| CloudWatch Logs | poucos MB | 5 GB | US$ 0 |
| S3 — jar + zip | ~30 MB | 5 GB (12 meses) | ~US$ 0 |

**Total esperado: US$ 0/mês, para sempre** — nada aqui depende da idade da
conta. O uso fica uma ou duas ordens de grandeza abaixo de cada limite.

Coisas que mudariam essa conta:

- **Muitos alvos no coletor.** A capacidade de escrita provisionada é 5/s por
  tabela. Cada alvo é uma escrita a cada 5 minutos; caberiam centenas antes de
  apertar.
- **Painel deixado aberto o tempo todo, em muitas abas.** `painel.js` atualiza
  a cada 30s; cada ciclo dispara 7 requisições (sistemas + disponibilidade +
  eventos, por sistema). Uso ocasional não chega perto do limite; várias abas
  abertas 24h/dia, somadas, poderiam se aproximar dele — ainda assim de graça,
  só vale saber que existe um teto.
- **Retenção de log.** Fixada em 7 dias nos dois templates. Se a Lambda criar o
  grupo sozinha, a retenção é *infinita* e o armazenamento cresce para sempre.

## Decisões que valem revisar

### Do coletor

**Permissão mínima no IAM.** O papel do coletor concede apenas `PutItem` e
`BatchWriteItem`, apenas na tabela de verificações. Sem leitura, sem `DeleteItem`
e sem `Resource: "*"`. Se a função for comprometida, o alcance dela para aí.

**A lista de alvos entra na hora do deploy.** É o parâmetro `Alvos`, marcado com
`NoEcho` para não aparecer no console nem nos eventos da pilha. Numa instância
privada ela carrega URL interna, e por isso não tem valor padrão nem aparece em
arquivo versionado.

### Da API

**Permissão dividida por operação, não por tabela.** O papel da API só tem
`Query` na tabela de verificações (ela nunca escreve lá — quem grava é o
coletor) e `Query` + `PutItem` na de eventos (lê e recebe `POST /eventos`).
Reflete exatamente o que `RepositorioDeVerificacoesDynamo` e
`RepositorioDeEventosDynamo` chamam no código, nem uma permissão a mais.

**CORS resolvido só num lugar.** A `AWS::Lambda::Url` não declara bloco `Cors`
— quem trata isso é a própria aplicação Spring (`ConfiguracaoCors.java`, já
testado). Configurar CORS nos dois lugares faria o navegador ver dois valores
de `Access-Control-Allow-Origin` na mesma resposta e recusar por inconsistência.

**Sem permissão de criar tabela.** `SENTINELA_DYNAMO_CRIAR_TABELAS` fica de
fora das variáveis de ambiente da API, e o papel IAM não inclui `CreateTable`.
As tabelas já existem, criadas pela pilha de tabelas — a API só lê e grava
item, nunca administra schema.

**Partida a frio medida na AWS de verdade, não estimada.** Primeiro deploy:
`Started SentinelaApiApplication in 8.229 seconds`. Isso passou dos ~9,8s de
inicialização com CPU extra que a Lambda oferece antes de cobrar tempo normal
(`Init Duration: 9838.68 ms` no log), e `AWS_LWA_ASYNC_INIT` funcionou
exatamente como documentado: em vez de reiniciar a função do zero, empurrou o
resto da espera para a primeira invocação (`Billed Duration: 10616 ms` nela),
dentro do `Timeout` de 30s configurado. As invocações seguintes, com a função
já quente, vieram em milissegundos: 15 ms, 126 ms, 4 ms. Na prática: a
primeira visita ao painel depois de um período ocioso demora alguns segundos
a mais; visitas seguintes são rápidas.

### Comuns às duas

**Nenhuma chave de acesso.** As Lambdas recebem credenciais temporárias pelo
papel IAM, que giram sozinhas. Não há `AWS_ACCESS_KEY_ID` em lugar nenhum do
código, do template ou da configuração.

**ARM em vez de x86.** As Lambdas em Graviton custam cerca de 20% menos pelo
mesmo trabalho, e o código Java roda igual nas duas arquiteturas.

**Memória acima do mínimo.** Na Lambda, memória e CPU andam juntas, e a
cobrança é por gigabyte-segundo — dobrar a memória de uma função que termina na
metade do tempo custa o mesmo. O coletor usa 512 MB; a API usa 1024 MB, porque
subir um contexto Spring inteiro processa mais classe do que o coletor faz, e é
exatamente nesse momento que alguém está esperando a tela carregar.

**`DeletionPolicy: Delete` nas tabelas.** Remover a pilha remove os dados. É
deliberado: o risco real aqui é recurso esquecido gerando custo, e "apaga tudo"
protege mais que "deixa órfão". Numa instância com dado que importe, troque para
`Retain`.

## Roteiro

Requer o [AWS CLI](https://aws.amazon.com/cli/) configurado (`aws configure`)
e Python 3 (só para empacotar o zip da API com a permissão de execução certa
no `run.sh` — ver o comentário em `infra/empacotar_api.py` sobre por que o
`zip` comum do Windows não serve para isso).

```bash
./infra/implantar.sh
```

O script não faz nada sozinho: ele imprime cada comando e pede confirmação antes
de executar. Leia antes de rodar.

A origem liberada por CORS na API vem de `SENTINELA_ORIGENS_PERMITIDAS`
(padrão: só `localhost:5500`, para testar local contra a API publicada). Depois
que a página for para o S3/CloudFront, atualize essa variável e rode o deploy
de novo — é uma atualização de pilha, sem perda de dado.

## Como remover tudo

Na ordem inversa — coletor e API primeiro, porque dependem das tabelas:

```bash
aws cloudformation delete-stack --stack-name sentinela-publico-api
aws cloudformation delete-stack --stack-name sentinela-publico-coletor
aws cloudformation delete-stack --stack-name sentinela-publico-tabelas
```

Isso apaga as funções, o agendamento, os papéis IAM, os logs e as tabelas.
Sobra apenas o bucket com o código, que precisa ser esvaziado antes de removido:

```bash
aws s3 rm s3://sentinela-publico-codigo-<sua-conta> --recursive
aws s3 rb s3://sentinela-publico-codigo-<sua-conta>
```

Depois de remover, confirme que não sobrou nada cobrando:

```bash
aws cloudformation list-stacks --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE
```

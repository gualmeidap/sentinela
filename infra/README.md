# Infraestrutura

O que sobe para a AWS, quanto custa e como remover.

**Nada aqui foi executado.** Os arquivos descrevem o que *seria* criado; nenhum
recurso existe até alguém rodar os comandos do [roteiro](#roteiro).

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
| `tabelas.yaml` | As duas tabelas do DynamoDB | Os dados sobrevivem à recriação do coletor |
| `coletor.yaml` | Lambda, papel IAM, grupo de log e o agendamento | Pode ser derrubado e recriado sem perder histórico |

A pilha do coletor **depende** da de tabelas: ela importa os nomes e ARNs de lá.

## Custo estimado

Com 3 alvos verificados a cada 5 minutos:

| Serviço | Uso mensal | Nível gratuito | Custo |
|---|---|---|---|
| Lambda — invocações | ~8.640 | 1.000.000 | US$ 0 |
| Lambda — computação | ~4.300 GB-s | 400.000 GB-s | US$ 0 |
| DynamoDB — capacidade | 10 de leitura, 10 de escrita | 25 e 25 | US$ 0 |
| DynamoDB — armazenamento | ~3 MB | 25 GB | US$ 0 |
| EventBridge — regra agendada | 8.640 disparos | ilimitado | US$ 0 |
| CloudWatch Logs | ~9 MB | 5 GB | US$ 0 |
| S3 — o jar | 7 MB | 5 GB (12 meses) | ~US$ 0 |

**Total esperado: US$ 0/mês.** O uso fica uma ou duas ordens de grandeza abaixo
de cada limite — não é um encaixe apertado.

Três coisas que mudariam essa conta:

- **Muitos alvos.** A capacidade de escrita provisionada é 5/s por tabela. Cada
  alvo é uma escrita a cada 5 minutos; caberiam centenas antes de apertar.
- **Retenção de log.** Está fixada em 7 dias no template. Se a Lambda criar o
  grupo sozinha, a retenção é *infinita* e o armazenamento cresce para sempre.
- **A EC2 do passo 6.** É o único item do projeto que sai do Always Free: a
  instância gratuita vale 750 h/mês por **12 meses**, e depois passa a cobrar.

## Decisões que valem revisar

**Permissão mínima no IAM.** O papel do coletor concede apenas `PutItem` e
`BatchWriteItem`, apenas na tabela de verificações. Sem leitura, sem `DeleteItem`
e sem `Resource: "*"`. Se a função for comprometida, o alcance dela para aí.

**Nenhuma chave de acesso.** A Lambda recebe credenciais temporárias pelo papel
IAM, que giram sozinhas. Não há `AWS_ACCESS_KEY_ID` em lugar nenhum do código,
do template ou da configuração.

**A lista de alvos entra na hora do deploy.** É o parâmetro `Alvos`, marcado com
`NoEcho` para não aparecer no console nem nos eventos da pilha. Numa instância
privada ela carrega URL interna, e por isso não tem valor padrão nem aparece em
arquivo versionado.

**ARM em vez de x86.** A Lambda em Graviton custa cerca de 20% menos pelo mesmo
trabalho, e o jar é Java puro — roda igual nas duas.

**512 MB de memória, não o mínimo.** Na Lambda, memória e CPU andam juntas, e a
cobrança é por gigabyte-segundo. Dobrar a memória de uma função que termina na
metade do tempo custa o mesmo, e a partida da JVM fica bem mais rápida.

**`DeletionPolicy: Delete` nas tabelas.** Remover a pilha remove os dados. É
deliberado: o risco real aqui é recurso esquecido gerando custo, e "apaga tudo"
protege mais que "deixa órfão". Numa instância com dado que importe, troque para
`Retain`.

## Roteiro

Requer o [AWS CLI](https://aws.amazon.com/cli/) configurado (`aws configure`).

```bash
./infra/implantar.sh
```

O script não faz nada sozinho: ele imprime cada comando e pede confirmação antes
de executar. Leia antes de rodar.

## Como remover tudo

Na ordem inversa — a pilha do coletor primeiro, porque ela depende da outra:

```bash
aws cloudformation delete-stack --stack-name sentinela-publico-coletor
```

```bash
aws cloudformation delete-stack --stack-name sentinela-publico-tabelas
```

Isso apaga a função, o agendamento, o papel IAM, os logs e as tabelas. Sobra
apenas o bucket com o jar, que precisa ser esvaziado antes de removido.

Depois de remover, confirme que não sobrou nada cobrando:

```bash
aws cloudformation list-stacks --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE
```

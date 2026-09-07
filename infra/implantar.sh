#!/usr/bin/env bash
#
# Sobe o coletor e a API para a AWS.
#
# Este script NAO faz nada sozinho: ele mostra cada comando e pede confirmacao
# antes de executar. Leia a saida antes de responder.
#
# Pre-requisitos:
#   - AWS CLI configurado (aws configure)
#   - Python 3 (usado so para empacotar o zip da API com o bit de execucao certo)
#   - alerta no AWS Budgets ja criado (ver README.md)
#
set -euo pipefail

AMBIENTE="${AMBIENTE:-publico}"
REGIAO="${AWS_REGION:-us-east-1}"
PILHA_TABELAS="sentinela-${AMBIENTE}-tabelas"
PILHA_COLETOR="sentinela-${AMBIENTE}-coletor"
PILHA_API="sentinela-${AMBIENTE}-api"
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_COLETOR="${RAIZ}/coletor/target/sentinela-coletor-0.0.1-SNAPSHOT.jar"
ZIP_API="${RAIZ}/infra/build/sentinela-api.zip"

confirmar() {
  echo
  echo "  $ $*"
  read -r -p "  executar? [s/N] " resposta
  case "$resposta" in
    s | S) "$@" ;;
    *) echo "  pulado."; return 1 ;;
  esac
}

titulo() {
  echo
  echo "=============================================================="
  echo " $1"
  echo "=============================================================="
}

# ---------------------------------------------------------------------------
titulo "0. Conferencias antes de comecar"

echo "  regiao   : ${REGIAO}"
echo "  ambiente : ${AMBIENTE}"
echo "  conta    : $(aws sts get-caller-identity --query Account --output text)"

if [ ! -f "${JAR_COLETOR}" ]; then
  echo
  echo "  ERRO: o jar do coletor nao existe em ${JAR_COLETOR}"
  echo "  Rode antes:  cd coletor && ./mvnw package"
  exit 1
fi
echo "  jar do coletor : $(du -h "${JAR_COLETOR}" | cut -f1)"

echo "  empacotando a API (gera run.sh e zipa com o jar)..."
python3 "${RAIZ}/infra/empacotar_api.py" || python "${RAIZ}/infra/empacotar_api.py"
echo "  zip da API      : $(du -h "${ZIP_API}" | cut -f1)"

# O bucket precisa de nome unico no mundo inteiro, entao entra o numero da conta.
CONTA="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="sentinela-${AMBIENTE}-codigo-${CONTA}"
CHAVE_COLETOR="coletor/sentinela-coletor.jar"
CHAVE_API="api/sentinela-api.zip"

echo
echo "  Este script vai, com sua confirmacao a cada passo:"
echo "    1. criar o bucket ${BUCKET}"
echo "    2. enviar o jar do coletor e o zip da API para la"
echo "    3. criar as duas tabelas do DynamoDB"
echo "    4. criar a Lambda do coletor, papel IAM e o agendamento de 5 em 5 minutos"
echo "    5. criar a Lambda da API, papel IAM e o endereco publico (Function URL)"
echo
echo "  Custo esperado: US\$ 0/mes. Ver infra/README.md para as contas."

# ---------------------------------------------------------------------------
titulo "1. Bucket para o codigo"

if aws s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
  echo "  o bucket ${BUCKET} ja existe."
else
  confirmar aws s3 mb "s3://${BUCKET}" --region "${REGIAO}" || true
  # Bloqueia acesso publico. Nenhum dos dois arquivos tem segredo dentro, mas
  # bucket aberto e o erro de configuracao mais comum da AWS e nao ha motivo
  # para correr o risco.
  confirmar aws s3api put-public-access-block \
    --bucket "${BUCKET}" \
    --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" || true
fi

# ---------------------------------------------------------------------------
titulo "2. Enviar o codigo"

confirmar aws s3 cp "${JAR_COLETOR}" "s3://${BUCKET}/${CHAVE_COLETOR}" || true
confirmar aws s3 cp "${ZIP_API}" "s3://${BUCKET}/${CHAVE_API}" || true

# ---------------------------------------------------------------------------
titulo "3. Tabelas do DynamoDB"

echo "  Cria: sentinela-${AMBIENTE}-verificacoes e sentinela-${AMBIENTE}-eventos"
echo "  Capacidade 5/5 em cada, dentro das 25 do Always Free."

confirmar aws cloudformation deploy \
  --template-file "${RAIZ}/infra/tabelas.yaml" \
  --stack-name "${PILHA_TABELAS}" \
  --region "${REGIAO}" \
  --parameter-overrides "Ambiente=${AMBIENTE}" || true

# ---------------------------------------------------------------------------
titulo "4. O coletor: funcao e agendamento"

# A lista de alvos NUNCA fica em arquivo versionado. Ela e digitada aqui, ou vem
# de uma variavel de ambiente que voce definiu na sua sessao.
if [ -z "${SENTINELA_ALVOS:-}" ]; then
  echo
  echo "  A lista de alvos nao esta em nenhum arquivo do repositorio, de proposito:"
  echo "  numa instancia privada ela carrega URL interna."
  echo
  echo "  Formato: id=url;id=url"
  echo "  Exemplo: portal-servicos=https://github.com;api-integracao=https://api.github.com"
  echo
  read -r -p "  alvos: " SENTINELA_ALVOS
fi

if [ -z "${SENTINELA_ALVOS}" ]; then
  echo "  sem alvos, nada a fazer."
  exit 1
fi

echo
echo "  A funcao vai rodar a cada 5 minutos, indefinidamente, ate a pilha ser removida."

confirmar aws cloudformation deploy \
  --template-file "${RAIZ}/infra/coletor.yaml" \
  --stack-name "${PILHA_COLETOR}" \
  --region "${REGIAO}" \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
  "Ambiente=${AMBIENTE}" \
  "BucketDoCodigo=${BUCKET}" \
  "ChaveDoCodigo=${CHAVE_COLETOR}" \
  "Alvos=${SENTINELA_ALVOS}" || true

# ---------------------------------------------------------------------------
titulo "5. A API: funcao e endereco publico"

# Origens de CORS: quem pode ler a API pelo navegador. Sem a pagina publicada
# ainda (S3/CloudFront vem depois), o padrao aqui cobre o ambiente local -- da
# para editar painel.js e apontar para a URL publica e testar contra ela antes
# de publicar a pagina de verdade. Atualize esta variavel e rode o deploy de
# novo quando o CloudFront existir.
if [ -z "${SENTINELA_ORIGENS_PERMITIDAS:-}" ]; then
  SENTINELA_ORIGENS_PERMITIDAS="http://localhost:5500,http://127.0.0.1:5500"
fi
echo "  origens liberadas para CORS: ${SENTINELA_ORIGENS_PERMITIDAS}"

echo
echo "  Primeira vez usando este mecanismo (AWS Lambda Web Adapter) neste projeto:"
echo "  o codigo Spring Boot nao mudou, mas vale conferir os logs apos o deploy."

confirmar aws cloudformation deploy \
  --template-file "${RAIZ}/infra/api.yaml" \
  --stack-name "${PILHA_API}" \
  --region "${REGIAO}" \
  --capabilities CAPABILITY_NAMED_IAM \
  --parameter-overrides \
  "Ambiente=${AMBIENTE}" \
  "BucketDoCodigo=${BUCKET}" \
  "ChaveDoCodigo=${CHAVE_API}" \
  "OrigensPermitidas=${SENTINELA_ORIGENS_PERMITIDAS}" || true

# ---------------------------------------------------------------------------
titulo "Pronto"

URL_DA_API="$(aws cloudformation describe-stacks --stack-name "${PILHA_API}" --region "${REGIAO}" \
  --query "Stacks[0].Outputs[?OutputKey=='UrlPublica'].OutputValue" --output text 2>/dev/null || echo "<ainda nao criada>")"

cat <<FIM

  URL PUBLICA DA API:

    ${URL_DA_API}

  Teste rapido:

    curl ${URL_DA_API}ping

  Coletor -- disparar uma rodada agora, sem esperar os 5 minutos:

    aws lambda invoke --function-name sentinela-${AMBIENTE}-coletor /dev/stdout

  Acompanhar os logs:

    aws logs tail /aws/lambda/sentinela-${AMBIENTE}-coletor --follow
    aws logs tail /aws/lambda/sentinela-${AMBIENTE}-api --follow

  Ver o que foi gravado:

    aws dynamodb scan --table-name sentinela-${AMBIENTE}-verificacoes --max-items 5

  REMOVER TUDO (na ordem, coletor e api dependem das tabelas):

    aws cloudformation delete-stack --stack-name ${PILHA_API}
    aws cloudformation delete-stack --stack-name ${PILHA_COLETOR}
    aws cloudformation delete-stack --stack-name ${PILHA_TABELAS}
    aws s3 rm s3://${BUCKET} --recursive && aws s3 rb s3://${BUCKET}

FIM

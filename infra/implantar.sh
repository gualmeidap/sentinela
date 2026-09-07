#!/usr/bin/env bash
#
# Sobe o coletor para a AWS.
#
# Este script NAO faz nada sozinho: ele mostra cada comando e pede confirmacao
# antes de executar. Leia a saida antes de responder.
#
# Pre-requisitos:
#   - AWS CLI configurado (aws configure)
#   - alerta no AWS Budgets ja criado (ver README.md)
#
set -euo pipefail

AMBIENTE="${AMBIENTE:-publico}"
REGIAO="${AWS_REGION:-us-east-1}"
PILHA_TABELAS="sentinela-${AMBIENTE}-tabelas"
PILHA_COLETOR="sentinela-${AMBIENTE}-coletor"
RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${RAIZ}/coletor/target/sentinela-coletor-0.0.1-SNAPSHOT.jar"

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

if [ ! -f "${JAR}" ]; then
  echo
  echo "  ERRO: o jar nao existe em ${JAR}"
  echo "  Rode antes:  cd coletor && ./mvnw package"
  exit 1
fi
echo "  jar      : $(du -h "${JAR}" | cut -f1)"

# O bucket precisa de nome unico no mundo inteiro, entao entra o numero da conta.
CONTA="$(aws sts get-caller-identity --query Account --output text)"
BUCKET="sentinela-${AMBIENTE}-codigo-${CONTA}"
CHAVE="coletor/sentinela-coletor.jar"

echo
echo "  Este script vai, com sua confirmacao a cada passo:"
echo "    1. criar o bucket ${BUCKET}"
echo "    2. enviar o jar para la"
echo "    3. criar as duas tabelas do DynamoDB"
echo "    4. criar a Lambda, o papel IAM e o agendamento de 5 em 5 minutos"
echo
echo "  Custo esperado: US\$ 0/mes. Ver infra/README.md para as contas."

# ---------------------------------------------------------------------------
titulo "1. Bucket para o codigo"

if aws s3api head-bucket --bucket "${BUCKET}" 2>/dev/null; then
  echo "  o bucket ${BUCKET} ja existe."
else
  confirmar aws s3 mb "s3://${BUCKET}" --region "${REGIAO}" || true
  # Bloqueia acesso publico. O jar nao tem segredo dentro, mas bucket aberto e
  # o erro de configuracao mais comum da AWS e nao ha motivo para correr o risco.
  confirmar aws s3api put-public-access-block \
    --bucket "${BUCKET}" \
    --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" || true
fi

# ---------------------------------------------------------------------------
titulo "2. Enviar o jar"

confirmar aws s3 cp "${JAR}" "s3://${BUCKET}/${CHAVE}" || true

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
titulo "4. A funcao e o agendamento"

# A lista de alvos NUNCA fica em arquivo versionado. Ela e digitada aqui, ou vem
# de uma variavel de ambiente que voce definiu na sua sessao.
if [ -z "${SENTINELA_ALVOS:-}" ]; then
  echo
  echo "  A lista de alvos nao esta em nenhum arquivo do repositorio, de proposito:"
  echo "  numa instancia privada ela carrega URL interna."
  echo
  echo "  Formato: id=url;id=url"
  echo "  Exemplo: portal=https://portal.example.com;api=https://api.example.com"
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
  "ChaveDoCodigo=${CHAVE}" \
  "Alvos=${SENTINELA_ALVOS}" || true

# ---------------------------------------------------------------------------
titulo "Pronto"

cat <<FIM

  Disparar uma rodada agora, sem esperar os 5 minutos:

    aws lambda invoke --function-name sentinela-${AMBIENTE}-coletor /dev/stdout

  Acompanhar os logs:

    aws logs tail /aws/lambda/sentinela-${AMBIENTE}-coletor --follow

  Ver o que foi gravado:

    aws dynamodb scan --table-name sentinela-${AMBIENTE}-verificacoes --max-items 5

  REMOVER TUDO (na ordem, o coletor depende das tabelas):

    aws cloudformation delete-stack --stack-name ${PILHA_COLETOR}
    aws cloudformation delete-stack --stack-name ${PILHA_TABELAS}
    aws s3 rm s3://${BUCKET} --recursive && aws s3 rb s3://${BUCKET}

FIM

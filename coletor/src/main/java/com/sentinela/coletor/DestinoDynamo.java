package com.sentinela.coletor;

import java.time.Duration;
import java.util.Map;

/**
 * Para onde o coletor grava, quando grava no DynamoDB.
 *
 * Tudo vem de variavel de ambiente porque e o que a Lambda tem: nao ha disco
 * para arquivo de configuracao la.
 *
 * Nao existe campo de chave de acesso. Na AWS, a Lambda recebe credenciais
 * temporarias por IAM Role, que o SDK pega sozinho e que giram sem ninguem
 * mexer. Chave fixa em variavel de ambiente seria credencial permanente
 * gravada em texto na configuracao da funcao.
 */
public record DestinoDynamo(String tabela, String endpoint, String regiao, Duration retencao) {

    public static final String VARIAVEL_TABELA = "SENTINELA_DYNAMO_TABELA";
    public static final String VARIAVEL_ENDPOINT = "SENTINELA_DYNAMO_ENDPOINT";
    public static final String VARIAVEL_REGIAO = "AWS_REGION";
    public static final String VARIAVEL_RETENCAO = "SENTINELA_RETENCAO_DIAS";

    private static final Duration RETENCAO_PADRAO = Duration.ofDays(30);
    private static final String REGIAO_PADRAO = "us-east-1";

    public DestinoDynamo {
        if (tabela == null || tabela.isBlank()) {
            throw new IllegalArgumentException("nome da tabela e obrigatorio");
        }
        regiao = (regiao == null || regiao.isBlank()) ? REGIAO_PADRAO : regiao;
        retencao = (retencao == null || retencao.isZero() || retencao.isNegative())
                ? RETENCAO_PADRAO : retencao;
    }

    /**
     * Le o destino do ambiente. Vazio quando nao ha tabela configurada -- caso
     * em que o coletor apenas imprime, que e como ele roda na sua maquina.
     */
    public static java.util.Optional<DestinoDynamo> doAmbiente(Map<String, String> ambiente) {
        String tabela = ambiente.get(VARIAVEL_TABELA);
        if (tabela == null || tabela.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new DestinoDynamo(
                tabela.trim(),
                ambiente.get(VARIAVEL_ENDPOINT),
                ambiente.get(VARIAVEL_REGIAO),
                retencaoDe(ambiente.get(VARIAVEL_RETENCAO))));
    }

    /** Verdadeiro quando aponta para um DynamoDB que nao e o da AWS. */
    public boolean temEndpointProprio() {
        return endpoint != null && !endpoint.isBlank();
    }

    private static Duration retencaoDe(String valor) {
        if (valor == null || valor.isBlank()) {
            return RETENCAO_PADRAO;
        }
        try {
            return Duration.ofDays(Long.parseLong(valor.trim()));
        } catch (NumberFormatException naoENumero) {
            throw new IllegalArgumentException(VARIAVEL_RETENCAO + " precisa ser um numero de dias");
        }
    }
}

package com.sentinela.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao de acesso ao DynamoDB.
 *
 * O endpoint vazio e o caso normal: o SDK descobre sozinho o endereco real da
 * AWS a partir da regiao. Preencher o endpoint e o que aponta a aplicacao para
 * o DynamoDB Local rodando na sua maquina.
 *
 * Nao ha campo de chave de acesso aqui, e isso e proposital. Na AWS, a EC2 e a
 * Lambda recebem credenciais temporarias por IAM Role, que o SDK pega sozinho e
 * que giram sem ninguem mexer. Chave fixa em configuracao seria credencial
 * permanente escrita em arquivo -- exatamente o que a IAM Role existe para
 * evitar.
 */
@ConfigurationProperties(prefix = "sentinela.dynamo")
public record PropriedadesDoDynamo(
        String endpoint,
        String regiao,
        String tabelaVerificacoes,
        String tabelaEventos,
        int retencaoDias,
        boolean criarTabelas) {

    public PropriedadesDoDynamo {
        regiao = vazio(regiao) ? "us-east-1" : regiao;
        tabelaVerificacoes = vazio(tabelaVerificacoes) ? "sentinela-verificacoes" : tabelaVerificacoes;
        tabelaEventos = vazio(tabelaEventos) ? "sentinela-eventos" : tabelaEventos;
        retencaoDias = retencaoDias <= 0 ? 30 : retencaoDias;
    }

    /** Verdadeiro quando aponta para um DynamoDB que nao e o da AWS. */
    public boolean temEndpointProprio() {
        return !vazio(endpoint);
    }

    private static boolean vazio(String valor) {
        return valor == null || valor.isBlank();
    }
}

package com.sentinela.api.persistencia;

import com.sentinela.api.config.PropriedadesDoDynamo;
import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

/**
 * Monta o cliente do DynamoDB.
 *
 * Existe fora do Spring para que o teste de integracao possa montar o mesmo
 * cliente sem subir contexto nenhum -- se a montagem morasse numa @Bean, o
 * teste testaria uma configuracao diferente da que roda em producao.
 */
public final class FabricaDeClienteDynamo {

    private FabricaDeClienteDynamo() {
    }

    public static DynamoDbClient criar(PropriedadesDoDynamo propriedades) {
        DynamoDbClientBuilder construtor = DynamoDbClient.builder()
                .region(Region.of(propriedades.regiao()))
                // Cliente HTTP simples do JDK. A API e sincrona e faz poucas
                // chamadas por requisicao; o cliente assincrono padrao traria
                // um servidor de rede inteiro sem nenhum ganho aqui.
                .httpClientBuilder(UrlConnectionHttpClient.builder());

        if (propriedades.temEndpointProprio()) {
            // Apontando para o DynamoDB Local. Ele exige credenciais no formato
            // certo mas nao as valida, entao qualquer par serve -- e deixar
            // explicito que sao falsas evita que alguem ache que ha segredo aqui.
            construtor.endpointOverride(URI.create(propriedades.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        // Sem endpoint proprio, e a AWS de verdade: o SDK procura as credenciais
        // sozinho, e na EC2 ou na Lambda elas vem da IAM Role, temporarias e
        // renovadas automaticamente. Nenhuma chave passa por aqui.

        return construtor.build();
    }
}

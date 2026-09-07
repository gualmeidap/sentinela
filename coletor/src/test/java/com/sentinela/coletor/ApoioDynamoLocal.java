package com.sentinela.coletor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Apoio para os testes que gravam no DynamoDB Local.
 *
 * Sobe com "docker compose up -d" na raiz do repositorio. Sem ele, os testes
 * que dependem do banco sao pulados em vez de falharem; no CI o container e
 * declarado no workflow, entao la eles sempre rodam.
 */
public final class ApoioDynamoLocal {

    public static final String ENDPOINT = "http://localhost:8000";
    private static final int PORTA = 8000;

    private ApoioDynamoLocal() {
    }

    public static boolean disponivel() {
        try (Socket tomada = new Socket()) {
            tomada.connect(new InetSocketAddress("localhost", PORTA), 500);
            return true;
        } catch (IOException foraDoAr) {
            return false;
        }
    }

    /** Destino apontando para o banco local, com tabela propria para cada teste. */
    public static DestinoDynamo destino() {
        return new DestinoDynamo(
                "coletor-teste-" + UUID.randomUUID().toString().substring(0, 8),
                ENDPOINT,
                "us-east-1",
                Duration.ofDays(30));
    }

    /**
     * Cria a tabela com as MESMAS chaves que o api/ cria.
     *
     * Se estas chaves divergirem das de la, a gravacao continua funcionando e o
     * painel fica vazio -- sem erro em lugar nenhum. Por isso os nomes vem das
     * constantes de ChavesDoBanco, e nao escritos a mao aqui.
     */
    public static DynamoDbClient clienteComTabela(DestinoDynamo destino) {
        DynamoDbClient cliente = RegistroNoDynamo.criarCliente(destino);
        cliente.createTable(CreateTableRequest.builder()
                .tableName(destino.tabela())
                .keySchema(
                        KeySchemaElement.builder().attributeName(ChavesDoBanco.PARTICAO)
                                .keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName(ChavesDoBanco.ORDENACAO)
                                .keyType(KeyType.RANGE).build())
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName(ChavesDoBanco.PARTICAO)
                                .attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName(ChavesDoBanco.ORDENACAO)
                                .attributeType(ScalarAttributeType.S).build())
                .billingMode(BillingMode.PROVISIONED)
                .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5L).writeCapacityUnits(5L).build())
                .build());
        cliente.waiter().waitUntilTableExists(pedido -> pedido.tableName(destino.tabela()));
        return cliente;
    }
}

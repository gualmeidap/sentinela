package com.sentinela.coletor;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

/**
 * Grava a rodada no DynamoDB.
 *
 * Aqui o coletor e a API finalmente se encontram -- no banco, e em nenhum outro
 * lugar. O coletor escreve e vai embora; a API le quando alguem abre o painel.
 * Nenhum dos dois sabe que o outro existe.
 */
public class RegistroNoDynamo implements RegistroDeVerificacoes, AutoCloseable {

    /** Limite do BatchWriteItem imposto pelo proprio DynamoDB. */
    private static final int MAXIMO_POR_LOTE = 25;

    private final DynamoDbClient cliente;
    private final DestinoDynamo destino;

    public RegistroNoDynamo(DestinoDynamo destino) {
        this(destino, criarCliente(destino));
    }

    /** Construtor para o teste injetar um cliente ja apontado ao banco local. */
    RegistroNoDynamo(DestinoDynamo destino, DynamoDbClient cliente) {
        this.destino = destino;
        this.cliente = cliente;
    }

    static DynamoDbClient criarCliente(DestinoDynamo destino) {
        DynamoDbClientBuilder construtor = DynamoDbClient.builder()
                .region(Region.of(destino.regiao()))
                // Cliente HTTP simples do JDK: a Lambda faz uma escrita e morre.
                // O cliente assincrono padrao traria um servidor de rede inteiro
                // para ser carregado em toda invocacao, sem nenhum ganho.
                .httpClientBuilder(UrlConnectionHttpClient.builder());

        if (destino.temEndpointProprio()) {
            construtor.endpointOverride(URI.create(destino.endpoint()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("local", "local")));
        }
        return construtor.build();
    }

    /**
     * Grava em lote.
     *
     * Uma chamada por verificacao funcionaria, mas na Lambda cada ida ao banco e
     * tempo cobrado. Com dez alvos, sao dez viagens de rede contra uma.
     */
    @Override
    public void registrar(List<Verificacao> verificacoes) {
        for (int i = 0; i < verificacoes.size(); i += MAXIMO_POR_LOTE) {
            List<Verificacao> lote = verificacoes.subList(
                    i, Math.min(i + MAXIMO_POR_LOTE, verificacoes.size()));
            escreverLote(lote);
        }
    }

    private void escreverLote(List<Verificacao> lote) {
        List<WriteRequest> pedidos = new ArrayList<>(lote.size());
        for (Verificacao verificacao : lote) {
            pedidos.add(WriteRequest.builder()
                    .putRequest(PutRequest.builder().item(paraItem(verificacao)).build())
                    .build());
        }

        Map<String, List<WriteRequest>> pendentes = Map.of(destino.tabela(), pedidos);

        // O BatchWriteItem pode aceitar parte do lote e devolver o resto em
        // UnprocessedItems -- normalmente por ter passado da capacidade
        // provisionada. Ignorar esse retorno perderia verificacoes em silencio,
        // e a fita ficaria com buracos que ninguem saberia explicar.
        for (int tentativa = 0; tentativa < 5 && !pendentes.isEmpty(); tentativa++) {
            BatchWriteItemResponse resposta = cliente.batchWriteItem(
                    BatchWriteItemRequest.builder().requestItems(pendentes).build());
            pendentes = resposta.unprocessedItems();

            if (!pendentes.isEmpty()) {
                esperarUmPouco(tentativa);
            }
        }

        if (!pendentes.isEmpty()) {
            throw new IllegalStateException(
                    "o DynamoDB nao aceitou parte das verificacoes apos varias tentativas");
        }
    }

    /** Espera crescente entre tentativas, para nao insistir no mesmo ritmo que causou a recusa. */
    private void esperarUmPouco(int tentativa) {
        try {
            Thread.sleep(50L * (1L << tentativa));
        } catch (InterruptedException interrompido) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido ao reenviar verificacoes", interrompido);
        }
    }

    private Map<String, AttributeValue> paraItem(Verificacao verificacao) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(ChavesDoBanco.PARTICAO, texto(verificacao.sistemaId()));
        item.put(ChavesDoBanco.ORDENACAO, texto(ChavesDoBanco.deInstante(verificacao.momento())));
        item.put(ChavesDoBanco.RESPONDEU, AttributeValue.fromBool(verificacao.respondeu()));
        item.put(ChavesDoBanco.EXPIRA_EM,
                numero(ChavesDoBanco.expiraEm(verificacao.momento(), destino.retencao())));

        if (verificacao.tempoRespostaMs() != null) {
            item.put(ChavesDoBanco.TEMPO_RESPOSTA, numero(verificacao.tempoRespostaMs()));
        }
        if (verificacao.motivo() != null) {
            item.put(ChavesDoBanco.MOTIVO, texto(verificacao.motivo().name()));
        }
        if (verificacao.statusHttp() != null) {
            item.put(ChavesDoBanco.STATUS_HTTP, numero(verificacao.statusHttp()));
        }
        return item;
    }

    @Override
    public void close() {
        cliente.close();
    }

    private static AttributeValue texto(String valor) {
        return AttributeValue.fromS(valor);
    }

    private static AttributeValue numero(Number valor) {
        return AttributeValue.fromN(String.valueOf(valor));
    }
}

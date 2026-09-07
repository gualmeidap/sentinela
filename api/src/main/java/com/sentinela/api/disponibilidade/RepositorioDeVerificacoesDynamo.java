package com.sentinela.api.disponibilidade;

import com.sentinela.api.config.PropriedadesDoDynamo;
import com.sentinela.api.persistencia.Chaves;
import com.sentinela.api.persistencia.CriadorDeTabelas;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Verificacoes gravadas no DynamoDB.
 *
 * Implementa a mesma interface do RepositorioEmMemoria. Trocar um pelo outro e
 * trocar o perfil ativo: nem o servico nem o controller sabem qual dos dois
 * esta em uso, que era exatamente o ponto de ter declarado a interface no
 * dominio la no passo 1.
 */
public class RepositorioDeVerificacoesDynamo implements RepositorioDeVerificacoes {

    private static final String CAMPO_RESPONDEU = "respondeu";
    private static final String CAMPO_TEMPO = "tempoRespostaMs";

    private final DynamoDbClient cliente;
    private final String tabela;
    private final Duration retencao;

    public RepositorioDeVerificacoesDynamo(DynamoDbClient cliente, PropriedadesDoDynamo propriedades) {
        this.cliente = cliente;
        this.tabela = propriedades.tabelaVerificacoes();
        this.retencao = Duration.ofDays(propriedades.retencaoDias());
    }

    /**
     * Grava a verificacao.
     *
     * Como a chave de ordenacao e o momento puro, gravar duas vezes o mesmo
     * sistema no mesmo instante substitui o item em vez de duplicar. A
     * idempotencia sai da modelagem, sem uma linha de codigo para garanti-la --
     * o que importa quando o coletor for uma Lambda, que pode ser invocada mais
     * de uma vez para o mesmo disparo.
     */
    @Override
    public void registrar(Verificacao verificacao) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(CriadorDeTabelas.PARTICAO_VERIFICACAO, texto(verificacao.sistemaId()));
        item.put(CriadorDeTabelas.ORDENACAO_VERIFICACAO, texto(Chaves.deInstante(verificacao.momento())));
        item.put(CAMPO_RESPONDEU, AttributeValue.fromBool(verificacao.respondeu()));
        if (verificacao.tempoRespostaMs() != null) {
            item.put(CAMPO_TEMPO, numero(verificacao.tempoRespostaMs()));
        }
        item.put(CriadorDeTabelas.ATRIBUTO_DE_EXPIRACAO,
                numero(Chaves.expiraEm(verificacao.momento(), retencao)));

        cliente.putItem(pedido -> pedido.tableName(tabela).item(item));
    }

    @Override
    public List<Verificacao> entre(String sistemaId, Instant inicio, Instant fim) {
        // Query, e nao Scan: a Query vai direto na particao do sistema e le so a
        // faixa de tempo pedida. O Scan leria a tabela inteira -- e e ele que
        // fica lento e caro quando o historico cresce.
        QueryRequest pedido = QueryRequest.builder()
                .tableName(tabela)
                .keyConditionExpression("#particao = :sistema AND #ordenacao BETWEEN :de AND :ate")
                .expressionAttributeNames(Map.of(
                        "#particao", CriadorDeTabelas.PARTICAO_VERIFICACAO,
                        "#ordenacao", CriadorDeTabelas.ORDENACAO_VERIFICACAO))
                .expressionAttributeValues(Map.of(
                        ":sistema", texto(sistemaId),
                        ":de", texto(Chaves.deInstante(inicio)),
                        ":ate", texto(Chaves.fimSimplesExclusivo(fim))))
                .build();

        // paginator: o DynamoDB devolve no maximo 1 MB por resposta e sinaliza
        // que ha mais. Sem percorrer as paginas, uma fita cheia voltaria cortada
        // -- e o erro apareceria so quando o volume crescesse.
        return cliente.queryPaginator(pedido).items().stream()
                .map(this::paraVerificacao)
                .toList();
    }

    @Override
    public Optional<Verificacao> ultima(String sistemaId) {
        QueryRequest pedido = QueryRequest.builder()
                .tableName(tabela)
                .keyConditionExpression("#particao = :sistema")
                .expressionAttributeNames(Map.of("#particao", CriadorDeTabelas.PARTICAO_VERIFICACAO))
                .expressionAttributeValues(Map.of(":sistema", texto(sistemaId)))
                // Ordem invertida mais limite 1: o banco entrega so o item mais
                // recente. Ler tudo para pegar o ultimo funcionaria hoje e
                // ficaria insustentavel com meses de historico.
                .scanIndexForward(false)
                .limit(1)
                .build();

        return cliente.query(pedido).items().stream().findFirst().map(this::paraVerificacao);
    }

    private Verificacao paraVerificacao(Map<String, AttributeValue> item) {
        AttributeValue tempo = item.get(CAMPO_TEMPO);
        return new Verificacao(
                item.get(CriadorDeTabelas.PARTICAO_VERIFICACAO).s(),
                Chaves.paraInstante(item.get(CriadorDeTabelas.ORDENACAO_VERIFICACAO).s()),
                Boolean.TRUE.equals(item.get(CAMPO_RESPONDEU).bool()),
                tempo == null ? null : Integer.valueOf(tempo.n()));
        // Campos que o coletor grave e a API ainda nao leia -- o motivo da
        // falha, por exemplo -- sao simplesmente ignorados aqui. O DynamoDB nao
        // tem esquema fixo, entao as duas pecas podem evoluir em ritmos
        // diferentes sem uma quebrar a outra.
    }

    private static AttributeValue texto(String valor) {
        return AttributeValue.fromS(valor);
    }

    private static AttributeValue numero(Number valor) {
        return AttributeValue.fromN(String.valueOf(valor));
    }
}

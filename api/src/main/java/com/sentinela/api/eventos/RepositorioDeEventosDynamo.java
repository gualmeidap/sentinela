package com.sentinela.api.eventos;

import com.sentinela.api.config.PropriedadesDoDynamo;
import com.sentinela.api.persistencia.Chaves;
import com.sentinela.api.persistencia.CriadorDeTabelas;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Eventos de negocio gravados no DynamoDB.
 *
 * A diferenca em relacao as verificacoes esta na chave de ordenacao, que aqui e
 * "momento#identificador". Dois eventos podem acontecer no mesmo milissegundo
 * -- duas redefinicoes de senha simultaneas sao normais --, e com a chave sendo
 * so o momento o segundo apagaria o primeiro em silencio. A contagem do dia
 * ficaria menor que a realidade, sem nenhum erro aparecer em lugar nenhum.
 */
public class RepositorioDeEventosDynamo implements RepositorioDeEventos {

    private static final String CAMPO_OCORRIDO_EM = "ocorridoEm";
    private static final String CAMPO_TIPO = "tipo";
    private static final String CAMPO_RESULTADO = "resultado";
    private static final String CAMPO_MOTIVO = "motivo";
    private static final String CAMPO_CONTEXTO = "contexto";

    private final DynamoDbClient cliente;
    private final String tabela;
    private final Duration retencao;

    public RepositorioDeEventosDynamo(DynamoDbClient cliente, PropriedadesDoDynamo propriedades) {
        this.cliente = cliente;
        this.tabela = propriedades.tabelaEventos();
        this.retencao = Duration.ofDays(propriedades.retencaoDias());
    }

    @Override
    public void registrar(Evento evento) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(CriadorDeTabelas.PARTICAO_EVENTO, texto(evento.sistema()));
        item.put(CriadorDeTabelas.ORDENACAO_EVENTO,
                texto(Chaves.deInstanteComId(evento.ocorridoEm(), UUID.randomUUID().toString())));

        // O momento e gravado de novo num campo proprio, alem de estar dentro da
        // chave. E redundante e custa alguns bytes, mas a leitura deixa de
        // depender de fatiar a chave -- e mudar o formato da chave um dia nao
        // vira uma migracao de dados.
        item.put(CAMPO_OCORRIDO_EM, texto(Chaves.deInstante(evento.ocorridoEm())));
        item.put(CAMPO_TIPO, texto(evento.tipo()));
        item.put(CAMPO_RESULTADO, texto(evento.resultado().name()));
        if (evento.motivo() != null) {
            item.put(CAMPO_MOTIVO, texto(evento.motivo()));
        }
        if (!evento.contexto().isEmpty()) {
            item.put(CAMPO_CONTEXTO, AttributeValue.fromM(evento.contexto().entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, campo -> texto(campo.getValue())))));
        }
        item.put(CriadorDeTabelas.ATRIBUTO_DE_EXPIRACAO,
                numero(Chaves.expiraEm(evento.ocorridoEm(), retencao)));

        cliente.putItem(pedido -> pedido.tableName(tabela).item(item));
    }

    @Override
    public List<Evento> entre(String sistema, Instant inicio, Instant fim) {
        QueryRequest pedido = QueryRequest.builder()
                .tableName(tabela)
                .keyConditionExpression("#particao = :sistema AND #ordenacao BETWEEN :de AND :ate")
                .expressionAttributeNames(Map.of(
                        "#particao", CriadorDeTabelas.PARTICAO_EVENTO,
                        "#ordenacao", CriadorDeTabelas.ORDENACAO_EVENTO))
                .expressionAttributeValues(Map.of(
                        ":sistema", texto(sistema),
                        // Chave composta: o limite inferior leva o separador
                        // para pegar todos os itens daquele instante, e o
                        // superior nao leva, para deixar de fora os do instante
                        // final.
                        ":de", texto(Chaves.inicioDaFaixa(inicio)),
                        ":ate", texto(Chaves.fimDaFaixaExclusivo(fim))))
                .build();

        return cliente.queryPaginator(pedido).items().stream().map(this::paraEvento).toList();
    }

    @Override
    public List<Evento> ultimos(String sistema, int limite) {
        if (limite <= 0) {
            return List.of();
        }
        QueryRequest pedido = QueryRequest.builder()
                .tableName(tabela)
                .keyConditionExpression("#particao = :sistema")
                .expressionAttributeNames(Map.of("#particao", CriadorDeTabelas.PARTICAO_EVENTO))
                .expressionAttributeValues(Map.of(":sistema", texto(sistema)))
                .scanIndexForward(false)
                .limit(limite)
                .build();

        return cliente.query(pedido).items().stream().map(this::paraEvento).toList();
    }

    private Evento paraEvento(Map<String, AttributeValue> item) {
        AttributeValue motivo = item.get(CAMPO_MOTIVO);
        AttributeValue contexto = item.get(CAMPO_CONTEXTO);

        return new Evento(
                item.get(CriadorDeTabelas.PARTICAO_EVENTO).s(),
                item.get(CAMPO_TIPO).s(),
                Resultado.valueOf(item.get(CAMPO_RESULTADO).s()),
                motivo == null ? null : motivo.s(),
                Chaves.paraInstante(item.get(CAMPO_OCORRIDO_EM).s()),
                contexto == null ? Map.of() : contexto.m().entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, campo -> campo.getValue().s())));
    }

    private static AttributeValue texto(String valor) {
        return AttributeValue.fromS(valor);
    }

    private static AttributeValue numero(Number valor) {
        return AttributeValue.fromN(String.valueOf(valor));
    }
}

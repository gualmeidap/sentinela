package com.sentinela.coletor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

/**
 * A gravacao do coletor no DynamoDB.
 *
 * Estes testes existem menos para provar que a escrita funciona e mais para
 * FIXAR O CONTRATO com o api/. As duas pecas nao compartilham codigo, entao o
 * unico acordo entre elas e o formato do item no banco. Se ele divergir, nada
 * quebra: o coletor grava, a API nao acha, e o painel fica vazio sem uma linha
 * de erro em lugar nenhum.
 */
@EnabledIf("dynamoLocalNoAr")
class RegistroNoDynamoTest {

    private static final Instant MOMENTO = Instant.parse("2026-09-07T15:30:00Z");

    private static DestinoDynamo destino;
    private static DynamoDbClient cliente;
    private static RegistroNoDynamo registro;

    static boolean dynamoLocalNoAr() {
        return ApoioDynamoLocal.disponivel();
    }

    @BeforeAll
    static void prepararBanco() {
        destino = ApoioDynamoLocal.destino();
        cliente = ApoioDynamoLocal.clienteComTabela(destino);
        registro = new RegistroNoDynamo(destino, cliente);
    }

    @AfterAll
    static void fechar() {
        if (cliente != null) {
            cliente.close();
        }
    }

    private List<Map<String, AttributeValue>> tudoNaTabela() {
        return cliente.scan(ScanRequest.builder().tableName(destino.tabela()).build()).items();
    }

    private Map<String, AttributeValue> itemDe(String sistemaId) {
        return tudoNaTabela().stream()
                .filter(item -> item.get(ChavesDoBanco.PARTICAO).s().equals(sistemaId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nao achei item de " + sistemaId));
    }

    @Test
    @DisplayName("a chave de ordenacao sai no formato exato que o api/ le")
    void formatoDaChaveEhOContratoComAApi() {
        registro.registrar(List.of(Verificacao.respondeu("contrato", MOMENTO, 120, 200)));

        // Este literal e o mesmo fixado no ChavesTest do api/. Os dois projetos
        // nao se enxergam; o literal repetido nos dois lados e o que denuncia
        // uma divergencia de formato.
        assertThat(itemDe("contrato").get(ChavesDoBanco.ORDENACAO).s())
                .isEqualTo("2026-09-07T15:30:00.000Z")
                .hasSize(24);
    }

    @Test
    @DisplayName("alvo no ar grava tempo de resposta e status, sem motivo")
    void alvoNoAr() {
        registro.registrar(List.of(Verificacao.respondeu("no-ar", MOMENTO, 142, 200)));

        Map<String, AttributeValue> item = itemDe("no-ar");
        assertThat(item.get(ChavesDoBanco.RESPONDEU).bool()).isTrue();
        assertThat(item.get(ChavesDoBanco.TEMPO_RESPOSTA).n()).isEqualTo("142");
        assertThat(item.get(ChavesDoBanco.STATUS_HTTP).n()).isEqualTo("200");
        assertThat(item).doesNotContainKey(ChavesDoBanco.MOTIVO);
    }

    @Test
    @DisplayName("alvo fora do ar grava o motivo, que a API pode ignorar sem quebrar")
    void alvoForaDoAr() {
        registro.registrar(List.of(
                Verificacao.falhou("fora", MOMENTO, 5000, MotivoDaFalha.TEMPO_ESGOTADO, null)));

        Map<String, AttributeValue> item = itemDe("fora");
        assertThat(item.get(ChavesDoBanco.RESPONDEU).bool()).isFalse();
        assertThat(item.get(ChavesDoBanco.MOTIVO).s()).isEqualTo("TEMPO_ESGOTADO");
        assertThat(item).doesNotContainKey(ChavesDoBanco.STATUS_HTTP);
    }

    @Test
    @DisplayName("todo item leva a data de expiracao, para o TTL apagar sozinho")
    void gravaOTtl() {
        registro.registrar(List.of(Verificacao.respondeu("com-ttl", MOMENTO, 100, 200)));

        long expira = Long.parseLong(itemDe("com-ttl").get(ChavesDoBanco.EXPIRA_EM).n());
        assertThat(Instant.ofEpochSecond(expira))
                .isEqualTo(Instant.parse("2026-10-07T15:30:00Z"));
    }

    @Test
    @DisplayName("gravar o mesmo sistema no mesmo instante sobrescreve, e nao duplica")
    void escritaEhIdempotente() {
        registro.registrar(List.of(Verificacao.respondeu("idempotente", MOMENTO, 111, 200)));
        registro.registrar(List.of(Verificacao.respondeu("idempotente", MOMENTO, 222, 200)));

        List<Map<String, AttributeValue>> doSistema = tudoNaTabela().stream()
                .filter(item -> item.get(ChavesDoBanco.PARTICAO).s().equals("idempotente"))
                .toList();

        assertThat(doSistema).hasSize(1);
        assertThat(doSistema.get(0).get(ChavesDoBanco.TEMPO_RESPOSTA).n()).isEqualTo("222");
    }

    @Test
    @DisplayName("lote maior que o limite de 25 do DynamoDB e gravado inteiro")
    void loteGrandeEhFatiado() {
        List<Verificacao> muitas = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            muitas.add(Verificacao.respondeu("lote-grande", MOMENTO.plusSeconds(i), 100 + i, 200));
        }

        registro.registrar(muitas);

        long gravadas = tudoNaTabela().stream()
                .filter(item -> item.get(ChavesDoBanco.PARTICAO).s().equals("lote-grande"))
                .count();
        assertThat(gravadas).isEqualTo(60);
    }

    @Test
    @DisplayName("lista vazia nao chama o banco nem quebra")
    void listaVaziaNaoQuebra() {
        registro.registrar(List.of());
    }
}

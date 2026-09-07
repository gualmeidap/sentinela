package com.sentinela.api.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinela.api.config.PropriedadesDoDynamo;
import com.sentinela.api.persistencia.ApoioDynamoLocal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * O repositorio de eventos contra um DynamoDB de verdade.
 *
 * O teste mais importante daqui e o de eventos simultaneos: e a razao de a
 * chave de ordenacao dos eventos ser composta, e o defeito que ela evita seria
 * invisivel -- nenhum erro, so uma contagem menor que a realidade.
 */
@EnabledIf("dynamoLocalNoAr")
class RepositorioDeEventosDynamoTest {

    private static final String SISTEMA = "portal-servicos";
    private static final Instant BASE = Instant.parse("2026-09-07T12:00:00Z");

    private static PropriedadesDoDynamo propriedades;
    private static DynamoDbClient cliente;
    private static RepositorioDeEventos repositorio;

    static boolean dynamoLocalNoAr() {
        return ApoioDynamoLocal.disponivel();
    }

    @BeforeAll
    static void prepararBanco() {
        propriedades = ApoioDynamoLocal.propriedades();
        cliente = ApoioDynamoLocal.clienteCom(propriedades);
        repositorio = new RepositorioDeEventosDynamo(cliente, propriedades);
    }

    @AfterAll
    static void fecharCliente() {
        if (cliente != null) {
            cliente.close();
        }
    }

    private Evento sucesso(String sistema, Instant momento) {
        return new Evento(sistema, "senha.redefinida", Resultado.SUCESSO, null, momento, Map.of());
    }

    @Test
    @DisplayName("dois eventos no mesmo instante sao gravados, e nao um por cima do outro")
    void eventosSimultaneosNaoSeApagam() {
        String sistema = "sistema-simultaneo";
        Instant mesmoInstante = BASE.plus(Duration.ofHours(1));

        repositorio.registrar(sucesso(sistema, mesmoInstante));
        repositorio.registrar(sucesso(sistema, mesmoInstante));
        repositorio.registrar(sucesso(sistema, mesmoInstante));

        List<Evento> lidos = repositorio.entre(sistema, mesmoInstante, mesmoInstante.plusSeconds(1));

        // Com a chave sendo so o instante, isto voltaria 1. E a contagem do dia
        // mostraria 1 onde houve 3, sem erro nenhum em lugar nenhum.
        assertThat(lidos).hasSize(3);
    }

    @Test
    @DisplayName("grava e le de volta preservando tipo, resultado, motivo e contexto")
    void preservaOEventoInteiro() {
        String sistema = "sistema-completo";
        Instant momento = BASE.plus(Duration.ofHours(2));

        repositorio.registrar(new Evento(sistema, "senha.redefinida", Resultado.FALHA,
                "dependencia_indisponivel", momento, Map.of("campus", "unidade_2")));

        Evento lido = repositorio.ultimos(sistema, 1).get(0);

        assertThat(lido.sistema()).isEqualTo(sistema);
        assertThat(lido.tipo()).isEqualTo("senha.redefinida");
        assertThat(lido.resultado()).isEqualTo(Resultado.FALHA);
        assertThat(lido.motivo()).isEqualTo("dependencia_indisponivel");
        assertThat(lido.ocorridoEm()).isEqualTo(momento);
        assertThat(lido.contexto()).containsEntry("campus", "unidade_2");
    }

    @Test
    @DisplayName("evento de sucesso volta sem motivo e sem contexto vazio quebrando nada")
    void sucessoSemMotivo() {
        String sistema = "sistema-sucesso";
        repositorio.registrar(sucesso(sistema, BASE.plus(Duration.ofHours(3))));

        Evento lido = repositorio.ultimos(sistema, 1).get(0);

        assertThat(lido.resultado()).isEqualTo(Resultado.SUCESSO);
        assertThat(lido.motivo()).isNull();
        assertThat(lido.contexto()).isEmpty();
    }

    @Test
    @DisplayName("a faixa do dia inclui o primeiro instante e exclui a virada")
    void faixaDoDiaEhSemiaberta() {
        String sistema = "sistema-do-dia";
        Instant inicioDoDia = Instant.parse("2026-09-07T00:00:00Z");
        Instant viradaDoDia = Instant.parse("2026-09-08T00:00:00Z");

        repositorio.registrar(sucesso(sistema, inicioDoDia));
        repositorio.registrar(sucesso(sistema, Instant.parse("2026-09-07T23:59:59.999Z")));
        repositorio.registrar(sucesso(sistema, viradaDoDia));

        List<Evento> doDia = repositorio.entre(sistema, inicioDoDia, viradaDoDia);

        assertThat(doDia).hasSize(2);
        assertThat(doDia).extracting(Evento::ocorridoEm).isSorted();
        assertThat(doDia.get(0).ocorridoEm()).isEqualTo(inicioDoDia);
    }

    @Test
    @DisplayName("os ultimos vem do mais recente para o mais antigo")
    void ultimosVemDoMaisRecente() {
        String sistema = "sistema-ordenado";
        for (int i = 0; i < 5; i++) {
            repositorio.registrar(sucesso(sistema, BASE.plus(Duration.ofMinutes(i))));
        }

        List<Evento> ultimos = repositorio.ultimos(sistema, 3);

        assertThat(ultimos).hasSize(3);
        assertThat(ultimos.get(0).ocorridoEm()).isEqualTo(BASE.plus(Duration.ofMinutes(4)));
        assertThat(ultimos.get(2).ocorridoEm()).isEqualTo(BASE.plus(Duration.ofMinutes(2)));
    }

    @Test
    @DisplayName("a particao isola os sistemas")
    void particaoIsolaOsSistemas() {
        repositorio.registrar(sucesso(SISTEMA, BASE));

        assertThat(repositorio.ultimos(SISTEMA, 10)).hasSize(1);
        assertThat(repositorio.ultimos("sistema-que-nunca-publicou", 10)).isEmpty();
    }

    @Test
    @DisplayName("limite zero ou negativo devolve vazio sem ir ao banco")
    void limiteInvalidoDevolveVazio() {
        assertThat(repositorio.ultimos(SISTEMA, 0)).isEmpty();
        assertThat(repositorio.ultimos(SISTEMA, -1)).isEmpty();
    }
}

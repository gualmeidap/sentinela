package com.sentinela.api.disponibilidade;

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
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * O repositorio de verificacoes contra um DynamoDB de verdade.
 *
 * Testar contra um dublê aqui nao provaria nada do que importa: o que pode dar
 * errado e a modelagem -- se a chave ordena certo, se a faixa pega os limites
 * certos, se a escrita repetida sobrescreve. Nada disso aparece num mock.
 */
@EnabledIf("dynamoLocalNoAr")
class RepositorioDeVerificacoesDynamoTest {

    private static final String SISTEMA = "portal-servicos";
    private static final Instant BASE = Instant.parse("2026-09-07T12:00:00Z");

    private static PropriedadesDoDynamo propriedades;
    private static DynamoDbClient cliente;
    private static RepositorioDeVerificacoes repositorio;

    static boolean dynamoLocalNoAr() {
        return ApoioDynamoLocal.disponivel();
    }

    @BeforeAll
    static void prepararBanco() {
        propriedades = ApoioDynamoLocal.propriedades();
        cliente = ApoioDynamoLocal.clienteCom(propriedades);
        repositorio = new RepositorioDeVerificacoesDynamo(cliente, propriedades);

        // 10 verificacoes de 5 em 5 minutos, a partir de BASE. A quarta falhou.
        for (int i = 0; i < 10; i++) {
            Instant momento = BASE.plus(Duration.ofMinutes(5L * i));
            repositorio.registrar(i == 3
                    ? Verificacao.naoRespondeu(SISTEMA, momento)
                    : Verificacao.respondeuEm(SISTEMA, momento, 100 + i));
        }
        repositorio.registrar(Verificacao.respondeuEm("outro-sistema", BASE, 999));
    }

    @AfterAll
    static void fecharCliente() {
        if (cliente != null) {
            cliente.close();
        }
    }

    @Test
    @DisplayName("grava e le de volta, em ordem cronologica")
    void gravaELeEmOrdem() {
        List<Verificacao> lidas = repositorio.entre(SISTEMA, BASE, BASE.plus(Duration.ofHours(1)));

        assertThat(lidas).hasSize(10);
        assertThat(lidas).extracting(Verificacao::momento).isSorted();
        assertThat(lidas.get(0).momento()).isEqualTo(BASE);
        assertThat(lidas.get(0).tempoRespostaMs()).isEqualTo(100);
    }

    @Test
    @DisplayName("a falha volta sem tempo de resposta, como foi gravada")
    void preservaAFalha() {
        List<Verificacao> lidas = repositorio.entre(SISTEMA, BASE, BASE.plus(Duration.ofHours(1)));

        Verificacao falha = lidas.get(3);
        assertThat(falha.respondeu()).isFalse();
        assertThat(falha.tempoRespostaMs()).isNull();
    }

    @Test
    @DisplayName("a faixa inclui o inicio e exclui o fim")
    void faixaEhSemiaberta() {
        // Uma janela que comeca na segunda verificacao e termina exatamente na
        // quarta: deve trazer a segunda e a terceira, e nao a quarta.
        List<Verificacao> lidas = repositorio.entre(
                SISTEMA, BASE.plus(Duration.ofMinutes(5)), BASE.plus(Duration.ofMinutes(15)));

        assertThat(lidas).hasSize(2);
        assertThat(lidas.get(0).momento()).isEqualTo(BASE.plus(Duration.ofMinutes(5)));
        assertThat(lidas.get(1).momento()).isEqualTo(BASE.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("a particao isola os sistemas: um nao enxerga o outro")
    void particaoIsolaOsSistemas() {
        List<Verificacao> doOutro = repositorio.entre("outro-sistema", BASE, BASE.plus(Duration.ofHours(1)));

        assertThat(doOutro).hasSize(1);
        assertThat(doOutro.get(0).tempoRespostaMs()).isEqualTo(999);
    }

    @Test
    @DisplayName("a ultima verificacao vem sem ler o historico inteiro")
    void ultimaVerificacao() {
        assertThat(repositorio.ultima(SISTEMA))
                .isPresent()
                .get()
                .satisfies(v -> {
                    assertThat(v.momento()).isEqualTo(BASE.plus(Duration.ofMinutes(45)));
                    assertThat(v.tempoRespostaMs()).isEqualTo(109);
                });
    }

    @Test
    @DisplayName("sistema sem nenhuma verificacao devolve vazio, e nao erro")
    void sistemaSemVerificacao() {
        assertThat(repositorio.ultima("sistema-que-nunca-foi-verificado")).isEmpty();
        assertThat(repositorio.entre("sistema-que-nunca-foi-verificado", BASE, BASE.plusSeconds(3600))).isEmpty();
    }

    @Test
    @DisplayName("item gravado pelo coletor, com campos que a API nao conhece, e lido sem quebrar")
    void leItemNoFormatoDoColetor() {
        String sistema = "sistema-do-coletor";
        Instant momento = Instant.parse("2026-09-07T21:00:00Z");

        // Reproduz exatamente o que o coletor grava numa falha: alem dos campos
        // que a API conhece, ele guarda o motivo, o tempo decorrido ate desistir
        // e, quando ha, o status HTTP.
        //
        // O tempo em falha ja derrubou a API com 500 uma vez: a Verificacao
        // recusa "nao respondeu com tempo de resposta", e o coletor grava
        // justamente isso. Este teste existe para essa combinacao nao voltar.
        cliente.putItem(pedido -> pedido.tableName(propriedades.tabelaVerificacoes()).item(Map.of(
                "sistemaId", AttributeValue.fromS(sistema),
                "momento", AttributeValue.fromS("2026-09-07T21:00:00.000Z"),
                "respondeu", AttributeValue.fromBool(false),
                "tempoRespostaMs", AttributeValue.fromN("5000"),
                "motivo", AttributeValue.fromS("TEMPO_ESGOTADO"),
                "expiraEm", AttributeValue.fromN("1791500000"))));

        List<Verificacao> lidas = repositorio.entre(sistema, momento, momento.plusSeconds(60));

        assertThat(lidas).hasSize(1);
        assertThat(lidas.get(0).respondeu()).isFalse();
        assertThat(lidas.get(0).tempoRespostaMs())
                .as("tempo ate desistir nao e tempo de resposta, e nao pode aparecer como tal")
                .isNull();
    }

    @Test
    @DisplayName("gravar duas vezes o mesmo instante sobrescreve, e nao duplica")
    void escritaEhIdempotente() {
        Instant momento = Instant.parse("2026-09-07T20:00:00Z");
        String sistema = "sistema-idempotente";

        repositorio.registrar(Verificacao.respondeuEm(sistema, momento, 111));
        repositorio.registrar(Verificacao.respondeuEm(sistema, momento, 222));

        List<Verificacao> lidas = repositorio.entre(sistema, momento, momento.plusSeconds(60));
        assertThat(lidas).hasSize(1);
        assertThat(lidas.get(0).tempoRespostaMs()).isEqualTo(222);
    }
}

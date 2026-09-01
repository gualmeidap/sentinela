package com.sentinela.api.disponibilidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testes das regras de disponibilidade. JUnit puro: nenhuma anotacao do Spring,
 * nenhum contexto subindo, nenhum banco. Rodam em milissegundos.
 *
 * A maioria usa janela de 1 hora em blocos de 15 minutos -- quatro blocos, cuja
 * aritmetica da para conferir de cabeca. A janela real de 24h so aparece onde a
 * quantidade de blocos e o que esta sendo verificado.
 */
class CalculadoraDeDisponibilidadeTest {

    private static final String SISTEMA = "portal-servicos";
    private static final Instant AGORA = Instant.parse("2026-08-30T12:00:00Z");
    private static final Duration UMA_HORA = Duration.ofHours(1);
    private static final Duration QUINZE_MIN = Duration.ofMinutes(15);

    /** Com fim as 12:00, a fita de 1h vai das 11:15 as 12:15 em quatro blocos. */
    private Fita fitaDeUmaHora(List<Verificacao> verificacoes) {
        return CalculadoraDeDisponibilidade.fita(verificacoes, AGORA, UMA_HORA, QUINZE_MIN);
    }

    @Test
    @DisplayName("24 horas em blocos de 15 minutos dao 96 blocos")
    void janelaPadraoTem96Blocos() {
        Fita fita = CalculadoraDeDisponibilidade.fita(List.of(), AGORA);

        assertThat(fita.blocos()).hasSize(96);
        assertThat(Duration.between(fita.inicio(), fita.fim())).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("a fita termina no limite de bloco seguinte, para nao tremer na tela")
    void fitaAlinhaOFimNoProximoLimiteDeBloco() {
        Fita fita = CalculadoraDeDisponibilidade.fita(
                List.of(), Instant.parse("2026-08-30T12:07:33Z"), UMA_HORA, QUINZE_MIN);

        assertThat(fita.fim()).isEqualTo(Instant.parse("2026-08-30T12:15:00Z"));
        assertThat(fita.inicio()).isEqualTo(Instant.parse("2026-08-30T11:15:00Z"));
    }

    @Test
    @DisplayName("bloco sem verificacao fica SEM_DADO, nao INDISPONIVEL")
    void blocoVazioFicaSemDado() {
        Fita fita = fitaDeUmaHora(List.of());

        assertThat(fita.blocos()).allMatch(bloco -> bloco.estado() == Bloco.Estado.SEM_DADO);
        assertThat(fita.percentual()).isEmpty();
    }

    @Test
    @DisplayName("bloco em que tudo respondeu fica DISPONIVEL")
    void blocoTodoRespondendoFicaDisponivel() {
        Fita fita = fitaDeUmaHora(List.of(
                Verificacao.respondeuEm(SISTEMA, Instant.parse("2026-08-30T11:35:00Z"), 100),
                Verificacao.respondeuEm(SISTEMA, Instant.parse("2026-08-30T11:40:00Z"), 120)));

        Bloco segundo = fita.blocos().get(1);
        assertThat(segundo.estado()).isEqualTo(Bloco.Estado.DISPONIVEL);
        assertThat(segundo.verificacoes()).isEqualTo(2);
        assertThat(segundo.falhas()).isZero();
    }

    @Test
    @DisplayName("bloco em que nada respondeu fica INDISPONIVEL")
    void blocoSemNenhumaRespostaFicaIndisponivel() {
        Fita fita = fitaDeUmaHora(List.of(
                Verificacao.naoRespondeu(SISTEMA, Instant.parse("2026-08-30T11:35:00Z")),
                Verificacao.naoRespondeu(SISTEMA, Instant.parse("2026-08-30T11:40:00Z"))));

        Bloco segundo = fita.blocos().get(1);
        assertThat(segundo.estado()).isEqualTo(Bloco.Estado.INDISPONIVEL);
        assertThat(segundo.falhas()).isEqualTo(2);
    }

    @Test
    @DisplayName("bloco com resposta e falha misturadas fica DEGRADADO")
    void blocoMistoFicaDegradado() {
        Fita fita = fitaDeUmaHora(List.of(
                Verificacao.respondeuEm(SISTEMA, Instant.parse("2026-08-30T11:35:00Z"), 100),
                Verificacao.naoRespondeu(SISTEMA, Instant.parse("2026-08-30T11:40:00Z"))));

        Bloco segundo = fita.blocos().get(1);
        assertThat(segundo.estado()).isEqualTo(Bloco.Estado.DEGRADADO);
        assertThat(segundo.verificacoes()).isEqualTo(2);
        assertThat(segundo.falhas()).isEqualTo(1);
    }

    @Test
    @DisplayName("o percentual soma a janela inteira, atravessando blocos")
    void percentualConsideraAJanelaToda() {
        Fita fita = fitaDeUmaHora(List.of(
                Verificacao.respondeuEm(SISTEMA, Instant.parse("2026-08-30T11:20:00Z"), 90),
                Verificacao.respondeuEm(SISTEMA, Instant.parse("2026-08-30T11:35:00Z"), 100),
                Verificacao.respondeuEm(SISTEMA, Instant.parse("2026-08-30T11:50:00Z"), 110),
                Verificacao.naoRespondeu(SISTEMA, Instant.parse("2026-08-30T12:05:00Z"))));

        assertThat(fita.verificacoes()).isEqualTo(4);
        assertThat(fita.falhas()).isEqualTo(1);
        assertThat(fita.percentual()).hasValue(75.0);
    }

    @Test
    @DisplayName("o percentual e arredondado em duas casas")
    void percentualArredondaEmDuasCasas() {
        Fita fita = fitaDeUmaHora(List.of(
                Verificacao.respondeuEm(SISTEMA, Instant.parse("2026-08-30T11:20:00Z"), 90),
                Verificacao.respondeuEm(SISTEMA, Instant.parse("2026-08-30T11:35:00Z"), 100),
                Verificacao.naoRespondeu(SISTEMA, Instant.parse("2026-08-30T11:50:00Z"))));

        assertThat(fita.percentual()).hasValue(66.67);
    }

    @Test
    @DisplayName("verificacao anterior a janela e ignorada")
    void verificacaoForaDaJanelaEIgnorada() {
        Fita fita = fitaDeUmaHora(List.of(
                Verificacao.naoRespondeu(SISTEMA, Instant.parse("2026-08-30T09:00:00Z"))));

        assertThat(fita.verificacoes()).isZero();
        assertThat(fita.percentual()).isEmpty();
    }

    @Test
    @DisplayName("janela que nao e multipla do bloco e recusada")
    void janelaNaoMultiplaDoBlocoERecusada() {
        assertThatThrownBy(() -> CalculadoraDeDisponibilidade.fita(
                List.of(), AGORA, Duration.ofMinutes(20), QUINZE_MIN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multipla");
    }

    @Test
    @DisplayName("bloco de duracao zero e recusado")
    void blocoZeradoERecusado() {
        assertThatThrownBy(() -> CalculadoraDeDisponibilidade.fita(
                List.of(), AGORA, UMA_HORA, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

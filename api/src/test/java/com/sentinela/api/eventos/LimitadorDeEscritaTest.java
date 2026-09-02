package com.sentinela.api.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O limite de escrita por aplicacao.
 *
 * O relogio e injetado, entao da para "esperar uma hora" sem esperar nada.
 */
class LimitadorDeEscritaTest {

    private static final String APLICACAO = "portal-servicos";
    private static final Instant MEIO_DA_HORA = Instant.parse("2026-09-02T14:30:00Z");

    /** Relogio que anda quando mandam, em vez de sozinho. */
    private static final class RelogioDeTeste extends Clock {
        private Instant instante;

        private RelogioDeTeste(Instant instante) {
            this.instante = instante;
        }

        private void avancar(Duration quanto) {
            instante = instante.plus(quanto);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return instante;
        }
    }

    @Test
    @DisplayName("aceita ate o limite e recusa dali em diante")
    void aceitaAteOLimite() {
        LimitadorDeEscrita limitador = new LimitadorDeEscrita(new RelogioDeTeste(MEIO_DA_HORA));

        assertThat(limitador.cabeNoLimite(APLICACAO, 3)).isTrue();
        assertThat(limitador.cabeNoLimite(APLICACAO, 3)).isTrue();
        assertThat(limitador.cabeNoLimite(APLICACAO, 3)).isTrue();
        assertThat(limitador.cabeNoLimite(APLICACAO, 3)).isFalse();
        assertThat(limitador.cabeNoLimite(APLICACAO, 3)).isFalse();
    }

    @Test
    @DisplayName("o silenciamento fica registrado, com o momento em que comecou")
    void registraOSilenciamento() {
        RelogioDeTeste relogio = new RelogioDeTeste(MEIO_DA_HORA);
        LimitadorDeEscrita limitador = new LimitadorDeEscrita(relogio);

        limitador.cabeNoLimite(APLICACAO, 1);
        assertThat(limitador.silenciadaDesde(APLICACAO)).isEmpty();

        limitador.cabeNoLimite(APLICACAO, 1);
        assertThat(limitador.silenciadaDesde(APLICACAO)).contains(MEIO_DA_HORA);
    }

    @Test
    @DisplayName("na virada da hora a contagem zera e o silenciamento se desfaz sozinho")
    void viradaDaHoraLiberaNovamente() {
        RelogioDeTeste relogio = new RelogioDeTeste(MEIO_DA_HORA);
        LimitadorDeEscrita limitador = new LimitadorDeEscrita(relogio);

        limitador.cabeNoLimite(APLICACAO, 1);
        assertThat(limitador.cabeNoLimite(APLICACAO, 1)).isFalse();

        relogio.avancar(Duration.ofHours(1));

        assertThat(limitador.cabeNoLimite(APLICACAO, 1)).isTrue();
        assertThat(limitador.silenciadaDesde(APLICACAO)).isEmpty();
    }

    @Test
    @DisplayName("uma aplicacao silenciada nao afeta as outras")
    void limiteEPorAplicacao() {
        LimitadorDeEscrita limitador = new LimitadorDeEscrita(new RelogioDeTeste(MEIO_DA_HORA));

        limitador.cabeNoLimite(APLICACAO, 1);
        assertThat(limitador.cabeNoLimite(APLICACAO, 1)).isFalse();

        assertThat(limitador.cabeNoLimite("outro-sistema", 1)).isTrue();
        assertThat(limitador.silenciadaDesde("outro-sistema")).isEmpty();
    }
}

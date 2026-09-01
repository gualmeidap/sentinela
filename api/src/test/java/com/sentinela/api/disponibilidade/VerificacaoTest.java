package com.sentinela.api.disponibilidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rejeicao de verificacao malformada.
 *
 * A validacao mora no construtor do record, entao o objeto invalido nunca chega
 * a existir -- nao ha estado meio certo circulando pelo sistema esperando
 * alguem lembrar de conferir.
 */
class VerificacaoTest {

    private static final String SISTEMA = "portal-servicos";
    private static final Instant MOMENTO = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    @DisplayName("verificacao que respondeu precisa de tempo de resposta")
    void respostaSemTempoERecusada() {
        assertThatThrownBy(() -> new Verificacao(SISTEMA, MOMENTO, true, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tempo de resposta");
    }

    @Test
    @DisplayName("verificacao que falhou nao pode ter tempo de resposta")
    void falhaComTempoERecusada() {
        assertThatThrownBy(() -> new Verificacao(SISTEMA, MOMENTO, false, 120))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tempo de resposta negativo e recusado")
    void tempoNegativoERecusado() {
        assertThatThrownBy(() -> new Verificacao(SISTEMA, MOMENTO, true, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sistema em branco e recusado")
    void sistemaEmBrancoERecusado() {
        assertThatThrownBy(() -> new Verificacao("   ", MOMENTO, true, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("momento nulo e recusado")
    void momentoNuloERecusado() {
        assertThatThrownBy(() -> new Verificacao(SISTEMA, null, true, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("as fabricas montam verificacoes coerentes")
    void fabricasProduzemVerificacoesValidas() {
        Verificacao ok = Verificacao.respondeuEm(SISTEMA, MOMENTO, 142);
        assertThat(ok.respondeu()).isTrue();
        assertThat(ok.tempoRespostaMs()).isEqualTo(142);

        Verificacao falha = Verificacao.naoRespondeu(SISTEMA, MOMENTO);
        assertThat(falha.respondeu()).isFalse();
        assertThat(falha.tempoRespostaMs()).isNull();
    }
}

package com.sentinela.api.disponibilidade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/**
 * A fita de disponibilidade: os blocos na ordem em que a tela pinta, mais o
 * resumo do periodo inteiro.
 *
 * O total e as falhas vem somados dos blocos, e nao recontados da lista de
 * verificacoes. Assim o percentual e a fita nunca podem se contradizer na tela.
 */
public record Fita(Instant inicio, Instant fim, List<Bloco> blocos, int verificacoes, int falhas) {

    public Fita {
        blocos = List.copyOf(blocos);
    }

    /**
     * Percentual de verificacoes que responderam, com duas casas.
     *
     * Vazio quando nao houve verificacao nenhuma na janela. "Nao sei" nao e a
     * mesma coisa que "zero por cento", e devolver 0.0 aqui faria a tela
     * anunciar uma queda que ninguem mediu.
     */
    public OptionalDouble percentual() {
        if (verificacoes == 0) {
            return OptionalDouble.empty();
        }
        double bruto = ((verificacoes - falhas) * 100.0) / verificacoes;
        return OptionalDouble.of(BigDecimal.valueOf(bruto)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue());
    }
}

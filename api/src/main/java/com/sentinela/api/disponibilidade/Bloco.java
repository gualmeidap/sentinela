package com.sentinela.api.disponibilidade;

import java.time.Instant;

/**
 * Um bloco da fita de 24 horas.
 *
 * Cada bloco resume as verificacoes de uma janela de 15 minutos num unico
 * estado -- que e o quadradinho que a tela pinta.
 */
public record Bloco(Instant inicio, Estado estado, int verificacoes, int falhas) {

    public enum Estado {
        /** Nenhuma verificacao caiu neste bloco. Pode ser o coletor que estava fora. */
        SEM_DADO,
        /** Todas as verificacoes do bloco responderam. */
        DISPONIVEL,
        /** Parte respondeu, parte nao. */
        DEGRADADO,
        /** Nenhuma verificacao do bloco respondeu. */
        INDISPONIVEL
    }

    /**
     * Decide o estado a partir da contagem.
     *
     * SEM_DADO e diferente de INDISPONIVEL de proposito: "nao medi" nao e a
     * mesma informacao que "medi e estava fora", e pintar os dois de vermelho
     * transformaria uma falha do coletor em falha do sistema observado.
     */
    public static Bloco de(Instant inicio, int verificacoes, int falhas) {
        if (verificacoes < 0 || falhas < 0 || falhas > verificacoes) {
            throw new IllegalArgumentException("contagem invalida para o bloco");
        }
        if (verificacoes == 0) {
            return new Bloco(inicio, Estado.SEM_DADO, 0, 0);
        }
        if (falhas == 0) {
            return new Bloco(inicio, Estado.DISPONIVEL, verificacoes, 0);
        }
        if (falhas == verificacoes) {
            return new Bloco(inicio, Estado.INDISPONIVEL, verificacoes, falhas);
        }
        return new Bloco(inicio, Estado.DEGRADADO, verificacoes, falhas);
    }
}

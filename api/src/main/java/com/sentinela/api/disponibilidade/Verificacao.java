package com.sentinela.api.disponibilidade;

import java.time.Instant;

/**
 * O resultado de uma checagem: em tal momento, o sistema respondeu ou nao, e em
 * quanto tempo.
 *
 * Quem grava isto e o coletor (passo 5). A API so le. As duas pecas nunca se
 * chamam: o banco e o unico ponto de encontro.
 *
 * "tempoRespostaMs" e Integer, e nao int, porque precisa poder ser nulo: quando
 * o alvo nao respondeu, nao existe tempo de resposta. Zero seria mentira -- diria
 * "respondeu instantaneamente".
 */
public record Verificacao(String sistemaId, Instant momento, boolean respondeu, Integer tempoRespostaMs) {

    public Verificacao {
        if (sistemaId == null || sistemaId.isBlank()) {
            throw new IllegalArgumentException("sistemaId e obrigatorio");
        }
        if (momento == null) {
            throw new IllegalArgumentException("momento e obrigatorio");
        }
        if (respondeu && tempoRespostaMs == null) {
            throw new IllegalArgumentException("verificacao que respondeu precisa de tempo de resposta");
        }
        if (!respondeu && tempoRespostaMs != null) {
            throw new IllegalArgumentException("verificacao que nao respondeu nao tem tempo de resposta");
        }
        if (tempoRespostaMs != null && tempoRespostaMs < 0) {
            throw new IllegalArgumentException("tempo de resposta nao pode ser negativo");
        }
    }

    public static Verificacao respondeuEm(String sistemaId, Instant momento, int tempoRespostaMs) {
        return new Verificacao(sistemaId, momento, true, tempoRespostaMs);
    }

    public static Verificacao naoRespondeu(String sistemaId, Instant momento) {
        return new Verificacao(sistemaId, momento, false, null);
    }
}

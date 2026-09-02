package com.sentinela.coletor;

import java.time.Instant;

/**
 * O resultado de uma checagem, pronto para ser gravado.
 *
 * Esta classe e quase igual a Verificacao do api/, e a duplicacao e
 * intencional: as duas pecas nao compartilham codigo. Se um jar comum
 * existisse, uma mudanca no formato de leitura da API poderia quebrar o
 * coletor rodando em producao, sem ninguem ter tocado nele.
 *
 * A diferenca em relacao a da API e o motivo da falha, que so o coletor sabe.
 */
public record Verificacao(
        String sistemaId,
        Instant momento,
        boolean respondeu,
        Integer tempoRespostaMs,
        MotivoDaFalha motivo,
        Integer statusHttp) {

    public Verificacao {
        if (sistemaId == null || sistemaId.isBlank()) {
            throw new IllegalArgumentException("sistemaId e obrigatorio");
        }
        if (momento == null) {
            throw new IllegalArgumentException("momento e obrigatorio");
        }
        if (respondeu && motivo != null) {
            throw new IllegalArgumentException("verificacao que respondeu nao tem motivo de falha");
        }
        if (!respondeu && motivo == null) {
            throw new IllegalArgumentException("verificacao que falhou precisa de motivo");
        }
        if (tempoRespostaMs != null && tempoRespostaMs < 0) {
            throw new IllegalArgumentException("tempo de resposta nao pode ser negativo");
        }
    }

    public static Verificacao respondeu(String sistemaId, Instant momento, int tempoRespostaMs, int statusHttp) {
        return new Verificacao(sistemaId, momento, true, tempoRespostaMs, null, statusHttp);
    }

    /**
     * Falha guarda o tempo decorrido mesmo assim: saber que o timeout estourou
     * aos 5000 ms e diferente de saber que a conexao morreu aos 30 ms.
     */
    public static Verificacao falhou(String sistemaId, Instant momento, int tempoDecorridoMs,
                                     MotivoDaFalha motivo, Integer statusHttp) {
        return new Verificacao(sistemaId, momento, false, tempoDecorridoMs, motivo, statusHttp);
    }
}

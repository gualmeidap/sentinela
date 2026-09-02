package com.sentinela.api.config;

import java.util.List;

/**
 * Um sistema autorizado a publicar eventos, como descrito na configuracao.
 *
 * Repare que o mesmo bloco de configuracao responde quatro perguntas de uma vez:
 * quem pode publicar (id), com que credencial (chave), que codigos pode usar
 * (tipos e motivos) e quanto volume pode gerar (limitePorHora). Manter isso
 * junto evita o caso de alguem cadastrar uma chave e esquecer do limite.
 *
 * A lista de tipos e motivos vive aqui, e nao num enum Java, para que
 * acrescentar um codigo novo seja mudanca de configuracao e nao recompilacao.
 * Continua sendo lista fechada: o que nao estiver declarado e recusado.
 */
public record AplicacaoPublicadora(
        String id,
        String chave,
        int limitePorHora,
        List<String> tipos,
        List<String> motivos,
        List<String> chavesDeContexto) {

    public AplicacaoPublicadora {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("aplicacao publicadora precisa de id");
        }
        if (chave == null || chave.isBlank()) {
            throw new IllegalArgumentException("aplicacao publicadora precisa de chave: " + id);
        }
        if (limitePorHora <= 0) {
            throw new IllegalArgumentException("limite-por-hora deve ser positivo em " + id);
        }
        tipos = tipos == null ? List.of() : List.copyOf(tipos);
        motivos = motivos == null ? List.of() : List.copyOf(motivos);
        chavesDeContexto = chavesDeContexto == null ? List.of() : List.copyOf(chavesDeContexto);
    }

    public boolean aceitaTipo(String tipo) {
        return tipos.contains(tipo);
    }

    public boolean aceitaMotivo(String motivo) {
        return motivos.contains(motivo);
    }

    public boolean aceitaChaveDeContexto(String chave) {
        return chavesDeContexto.contains(chave);
    }
}

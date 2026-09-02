package com.sentinela.api.eventos;

import java.time.Instant;
import java.util.Map;

/**
 * Um evento de negocio publicado por um sistema.
 *
 * Aqui mora so o que e verdade sempre, independente de configuracao: falha tem
 * motivo, sucesso nao tem, e nenhum campo obrigatorio vem em branco. Se um
 * codigo pertence ou nao a lista fechada depende da aplicacao que publicou, e
 * por isso e checado na RecepcaoDeEventos, nao aqui.
 *
 * Nao existe campo para nome, matricula, e-mail ou identificador de usuario --
 * nao por esquecimento, mas porque o portal responde "o que, onde, quando e com
 * que resultado", nunca "com quem". Quem precisa saber quem foi consulta o log
 * de auditoria do sistema de origem.
 */
public record Evento(
        String sistema,
        String tipo,
        Resultado resultado,
        String motivo,
        Instant ocorridoEm,
        Map<String, String> contexto) {

    public Evento {
        if (sistema == null || sistema.isBlank()) {
            throw new IllegalArgumentException("sistema e obrigatorio");
        }
        if (tipo == null || tipo.isBlank()) {
            throw new IllegalArgumentException("tipo e obrigatorio");
        }
        if (resultado == null) {
            throw new IllegalArgumentException("resultado e obrigatorio");
        }
        if (ocorridoEm == null) {
            throw new IllegalArgumentException("ocorridoEm e obrigatorio");
        }
        if (resultado == Resultado.FALHA && (motivo == null || motivo.isBlank())) {
            throw new IllegalArgumentException("falha precisa de motivo");
        }
        if (resultado == Resultado.SUCESSO && motivo != null) {
            throw new IllegalArgumentException("sucesso nao tem motivo");
        }
        contexto = contexto == null ? Map.of() : Map.copyOf(contexto);
    }

    public boolean falhou() {
        return resultado == Resultado.FALHA;
    }
}

package com.sentinela.api.eventos;

import java.time.Instant;
import java.util.List;

/**
 * Contrato de persistencia dos eventos, declarado pelo dominio.
 *
 * Mesmo desenho do RepositorioDeVerificacoes: trocar por DynamoDB no passo 4 e
 * escrever outra implementacao, sem tocar em servico nem controller.
 */
public interface RepositorioDeEventos {

    void registrar(Evento evento);

    /** Eventos de um sistema no intervalo [inicio, fim), em ordem cronologica. */
    List<Evento> entre(String sistema, Instant inicio, Instant fim);

    /** Os eventos mais recentes do sistema, do mais novo para o mais antigo. */
    List<Evento> ultimos(String sistema, int limite);
}

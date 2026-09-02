package com.sentinela.api.eventos;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

/**
 * Banco local dos eventos: um mapa em memoria, como o das verificacoes.
 *
 * Nao persiste entre reinicios, e no passo 4 vira DynamoDB -- onde o TTL de 30
 * dias vai expirar registro sozinho, sem rotina de limpeza para manter.
 */
@Repository
public class RepositorioDeEventosEmMemoria implements RepositorioDeEventos {

    private static final Comparator<Evento> POR_MOMENTO = Comparator.comparing(Evento::ocorridoEm);

    private final Map<String, List<Evento>> porSistema = new ConcurrentHashMap<>();

    @Override
    public void registrar(Evento evento) {
        porSistema.computeIfAbsent(evento.sistema(), sistema -> new CopyOnWriteArrayList<>())
                .add(evento);
    }

    @Override
    public List<Evento> entre(String sistema, Instant inicio, Instant fim) {
        return doSistema(sistema).stream()
                .filter(evento -> !evento.ocorridoEm().isBefore(inicio))
                .filter(evento -> evento.ocorridoEm().isBefore(fim))
                .sorted(POR_MOMENTO)
                .toList();
    }

    @Override
    public List<Evento> ultimos(String sistema, int limite) {
        if (limite <= 0) {
            return List.of();
        }
        return doSistema(sistema).stream()
                .sorted(POR_MOMENTO.reversed())
                .limit(limite)
                .toList();
    }

    private List<Evento> doSistema(String sistema) {
        return porSistema.getOrDefault(sistema, List.of());
    }
}

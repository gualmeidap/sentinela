package com.sentinela.api.disponibilidade;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Repository;

/**
 * O "banco local" do passo 1: um mapa em memoria.
 *
 * Nao sobrevive a um reinicio, e nao precisa. A alternativa obvia seria H2 com
 * JPA, mas isso ensinaria um modelo relacional -- tabelas, entidades, join --
 * que o DynamoDB do passo 4 nao usa, so para jogar fora depois. O que precisa
 * sobreviver a troca e a interface, nao a implementacao.
 *
 * ConcurrentHashMap e CopyOnWriteArrayList porque o Tomcat atende requisicoes
 * em varias threads ao mesmo tempo: um HashMap comum aqui daria corrupcao
 * silenciosa sob carga.
 */
@Repository
public class RepositorioEmMemoria implements RepositorioDeVerificacoes {

    private final Map<String, List<Verificacao>> porSistema = new ConcurrentHashMap<>();

    @Override
    public void registrar(Verificacao verificacao) {
        porSistema.computeIfAbsent(verificacao.sistemaId(), id -> new CopyOnWriteArrayList<>())
                .add(verificacao);
    }

    @Override
    public List<Verificacao> entre(String sistemaId, Instant inicio, Instant fim) {
        return doSistema(sistemaId).stream()
                .filter(verificacao -> !verificacao.momento().isBefore(inicio))
                .filter(verificacao -> verificacao.momento().isBefore(fim))
                .sorted(Comparator.comparing(Verificacao::momento))
                .toList();
    }

    @Override
    public Optional<Verificacao> ultima(String sistemaId) {
        return doSistema(sistemaId).stream()
                .max(Comparator.comparing(Verificacao::momento));
    }

    private List<Verificacao> doSistema(String sistemaId) {
        return porSistema.getOrDefault(sistemaId, List.of());
    }
}

package com.sentinela.api.disponibilidade;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * O contrato de persistencia, declarado pelo dominio e nao pelo banco.
 *
 * Trocar o banco local por DynamoDB (passo 4) e escrever outra classe que
 * implemente esta interface. Nem o servico nem o controller precisam saber:
 * eles dependem deste tipo, nao da tecnologia por tras.
 *
 * E o mesmo papel de um Protocol ou de uma classe abstrata no Python -- a
 * diferenca e que aqui o compilador cobra.
 */
public interface RepositorioDeVerificacoes {

    void registrar(Verificacao verificacao);

    /** Verificacoes de um sistema no intervalo [inicio, fim), em ordem cronologica. */
    List<Verificacao> entre(String sistemaId, Instant inicio, Instant fim);

    /** A verificacao mais recente do sistema, se houver alguma. */
    Optional<Verificacao> ultima(String sistemaId);
}

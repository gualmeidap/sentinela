package com.sentinela.api.sistema;

import java.net.URI;

/**
 * Um sistema que o portal observa: nome e URL, nada mais.
 *
 * "record" e uma classe imutavel cujo construtor, getters, equals, hashCode e
 * toString o compilador gera. E o parente mais proximo do dataclass do Python,
 * e serve bem para dado que so carrega valor.
 *
 * O bloco sem parametros abaixo e o construtor compacto: roda antes dos campos
 * serem atribuidos e existe para validar. Um objeto invalido nunca chega a
 * existir.
 */
public record SistemaMonitorado(String id, String nome, URI url) {

    public SistemaMonitorado {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id do sistema e obrigatorio");
        }
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("nome do sistema e obrigatorio");
        }
        if (url == null) {
            throw new IllegalArgumentException("url do sistema e obrigatoria");
        }
    }
}

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
        // url e opcional, e isso importa: a API nunca chama o alvo -- quem
        // verifica disponibilidade e o coletor, que recebe os enderecos por
        // variavel de ambiente. Aqui a url so seria devolvida na resposta e
        // impressa na tela, e a tela da instancia privada fica atras de uma
        // Function URL sem autenticacao. Endereco interno nao entra nesta
        // lista: a instancia privada declara id e nome, e mais nada.
    }
}

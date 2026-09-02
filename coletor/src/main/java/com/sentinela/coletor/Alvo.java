package com.sentinela.coletor;

import java.net.URI;

/**
 * Um sistema a verificar: um identificador e um endereco.
 *
 * O nome de exibicao nao esta aqui de proposito -- quem desenha a tela e a
 * API, e o coletor nao precisa saber como o sistema se chama para perguntar se
 * ele responde.
 */
public record Alvo(String id, URI url) {

    public Alvo {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("alvo sem id");
        }
        if (url == null) {
            throw new IllegalArgumentException("alvo sem url: " + id);
        }
        String esquema = url.getScheme();
        if (esquema == null || !(esquema.equals("http") || esquema.equals("https"))) {
            throw new IllegalArgumentException("alvo com url que nao e http nem https: " + id);
        }
        if (url.getHost() == null) {
            throw new IllegalArgumentException("alvo com url sem host: " + id);
        }
    }
}

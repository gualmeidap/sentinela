package com.sentinela.api.erro;

/**
 * A aplicacao passou do volume combinado e esta silenciada ate o fim da hora.
 * Vira 429.
 */
public class LimiteExcedido extends RuntimeException {

    private final transient int limitePorHora;

    public LimiteExcedido(int limitePorHora) {
        super("limite de escrita excedido");
        this.limitePorHora = limitePorHora;
    }

    public int limitePorHora() {
        return limitePorHora;
    }
}

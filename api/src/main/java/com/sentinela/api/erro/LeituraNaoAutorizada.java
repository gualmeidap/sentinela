package com.sentinela.api.erro;

/**
 * A leitura exigiu a chave de leitura e ela faltou ou nao confere.
 *
 * Nao carrega a chave recebida: o valor que alguem digitou errado e quase
 * sempre a chave certa com um engano, e nao tem por que ir para log nem para
 * resposta.
 */
public class LeituraNaoAutorizada extends RuntimeException {

    public LeituraNaoAutorizada() {
        super("leitura sem chave valida");
    }
}

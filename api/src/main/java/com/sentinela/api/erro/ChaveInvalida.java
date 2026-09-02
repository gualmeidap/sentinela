package com.sentinela.api.erro;

/**
 * Chave de aplicacao ausente ou desconhecida. Vira 401.
 *
 * Nao carrega a chave apresentada: ela iria parar no log, e chave em log e
 * chave vazada.
 */
public class ChaveInvalida extends RuntimeException {

    public ChaveInvalida() {
        super("chave de aplicacao ausente ou invalida");
    }
}

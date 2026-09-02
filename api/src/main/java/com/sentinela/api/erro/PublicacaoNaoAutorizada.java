package com.sentinela.api.erro;

/**
 * A chave e valida, mas o evento diz respeito a outro sistema. Vira 403.
 *
 * Uma chave publica pelo seu proprio sistema e por mais nenhum. Sem esta regra,
 * uma aplicacao comprometida poderia forjar eventos em nome das outras e o
 * painel inteiro deixaria de ser confiavel.
 */
public class PublicacaoNaoAutorizada extends RuntimeException {

    public PublicacaoNaoAutorizada() {
        super("a chave apresentada nao publica para o sistema informado");
    }
}

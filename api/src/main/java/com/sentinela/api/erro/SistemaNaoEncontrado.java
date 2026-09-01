package com.sentinela.api.erro;

/**
 * Pediram a disponibilidade de um sistema que nao esta no catalogo.
 *
 * Erro esperado, nao defeito: quem chama a API pode digitar um id errado. Por
 * isso vira 404 com corpo explicito no TratadorDeErros, e nao 500 com pilha de
 * excecao vazando.
 */
public class SistemaNaoEncontrado extends RuntimeException {

    private final transient String sistemaId;

    public SistemaNaoEncontrado(String sistemaId) {
        super("sistema monitorado nao encontrado: " + sistemaId);
        this.sistemaId = sistemaId;
    }

    public String sistemaId() {
        return sistemaId;
    }
}

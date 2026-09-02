package com.sentinela.api.eventos;

import java.util.Locale;

/**
 * O desfecho de um evento. Sao dois, e so dois.
 *
 * Um enum e uma lista fechada garantida pelo compilador: nao existe estado
 * "talvez" nem "parcial" circulando pelo sistema porque alguem digitou errado.
 */
public enum Resultado {
    SUCESSO,
    FALHA;

    /**
     * O JSON usa minusculas ("sucesso", "falha") por convencao da API. A
     * conversao acontece aqui, e nao numa configuracao global do Jackson, para
     * ficar visivel a quem le o codigo.
     */
    public static Resultado deTexto(String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return valueOf(texto.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException naoExiste) {
            return null;
        }
    }
}

package com.sentinela.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * A chave que protege os endpoints de LEITURA (tudo sob /sistemas).
 *
 * Em branco, a leitura e aberta -- e o caso da instancia publica, cujo painel e
 * a demonstracao e nao guarda nada sensivel. Preenchida, toda leitura exige o
 * cabecalho X-Chave-Leitura. A instancia privada usa isto porque a Function URL
 * da AWS e publica por construcao: sem a chave, quem descobrir o endereco le o
 * painel inteiro.
 *
 * "obrigatoria" existe para o erro silencioso: uma instancia privada que sobe
 * sem a chave nao falha, so fica aberta, e ninguem percebe. Com obrigatoria =
 * true, chave ausente derruba a subida -- que e o jeito barulhento de errar.
 *
 * A chave nunca aparece em mensagem de erro nem em log.
 */
@ConfigurationProperties(prefix = "sentinela.leitura")
public record PropriedadesDeLeitura(String chave, boolean obrigatoria) {

    /** Abaixo disto a chave e adivinhavel por tentativa; recusa em vez de aceitar. */
    static final int TAMANHO_MINIMO = 16;

    public PropriedadesDeLeitura {
        chave = chave == null ? "" : chave.strip();
        if (obrigatoria && chave.isEmpty()) {
            throw new IllegalStateException(
                    "a leitura exige chave nesta instancia, mas sentinela.leitura.chave esta vazia");
        }
        if (!chave.isEmpty() && chave.length() < TAMANHO_MINIMO) {
            throw new IllegalStateException(
                    "a chave de leitura e curta demais: use ao menos " + TAMANHO_MINIMO + " caracteres");
        }
    }

    public boolean protegida() {
        return !chave.isEmpty();
    }
}

package com.sentinela.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PropriedadesDeLeituraTest {

    private static final String CHAVE_VALIDA = "uma-chave-com-mais-de-dezesseis";

    @Test
    @DisplayName("obrigatoria sem chave derruba a subida, e nao deixa a instancia aberta em silencio")
    void obrigatoriaSemChaveFalha() {
        assertThatThrownBy(() -> new PropriedadesDeLeitura("", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PropriedadesDeLeitura(null, true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PropriedadesDeLeitura("   ", true))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("chave curta e recusada, e a mensagem de erro nao repete o valor")
    void chaveCurtaEhRecusada() {
        String curta = "curta-demais";

        assertThatThrownBy(() -> new PropriedadesDeLeitura(curta, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(curta);
    }

    @Test
    @DisplayName("sem chave e sem obrigatoriedade a leitura e aberta")
    void semChaveELeituraAberta() {
        assertThat(new PropriedadesDeLeitura("", false).protegida()).isFalse();
        assertThat(new PropriedadesDeLeitura(null, false).protegida()).isFalse();
    }

    @Test
    @DisplayName("chave valida protege, e os espacos em volta (comuns ao copiar e colar) sao ignorados")
    void chaveValidaProtege() {
        PropriedadesDeLeitura propriedades = new PropriedadesDeLeitura("  " + CHAVE_VALIDA + "\n", true);

        assertThat(propriedades.protegida()).isTrue();
        assertThat(propriedades.chave()).isEqualTo(CHAVE_VALIDA);
    }
}

package com.sentinela.api.ping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A raiz da API existe para que quem abrir so o endereco base -- sem saber que
 * precisa completar com /sistemas -- caia em algo que se explica, em vez da
 * pagina de erro generica do Spring. Isso aconteceu de verdade na primeira
 * visita a API publica, e este teste existe para nao regredir.
 */
@WebMvcTest(PingController.class)
class PingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /ping confirma que a aplicacao esta de pe")
    void pingRespondeOk() throws Exception {
        mockMvc.perform(get("/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.servico").value("sentinela-api"));
    }

    @Test
    @DisplayName("GET / devolve um ponto de partida, nao a pagina de erro do Spring")
    void raizDevolvePontoDePartida() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servico").value("sentinela-api"))
                .andExpect(jsonPath("$.endpoints.disponibilidade").value("/sistemas"));
    }
}

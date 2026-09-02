package com.sentinela.api.config;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinela.api.disponibilidade.ConsultaDeDisponibilidade;
import com.sentinela.api.disponibilidade.SistemasController;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CORS liberado demais e um erro silencioso: nada quebra, a pagina funciona, e
 * so muito depois alguem descobre que a API estava aberta para qualquer origem.
 * Por isso a regra tem teste -- inclusive o caso negativo, que e o que de fato
 * importa aqui.
 */
@WebMvcTest(SistemasController.class)
@Import(ConfiguracaoCors.class)
@TestPropertySource(properties = "sentinela.origens-permitidas=http://localhost:5500")
class ConfiguracaoCorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsultaDeDisponibilidade consulta;

    @Test
    @DisplayName("origem declarada recebe o cabecalho que autoriza a leitura")
    void origemPermitidaRecebeCabecalho() throws Exception {
        given(consulta.estadoDeTodos()).willReturn(List.of());

        mockMvc.perform(get("/sistemas").header("Origin", "http://localhost:5500"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"));
    }

    @Test
    @DisplayName("origem nao declarada e recusada")
    void origemDesconhecidaERecusada() throws Exception {
        mockMvc.perform(get("/sistemas").header("Origin", "https://pagina-qualquer.example.com"))
                .andExpect(status().isForbidden());
    }
}

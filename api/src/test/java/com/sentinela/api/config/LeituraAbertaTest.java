package com.sentinela.api.config;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Sem chave configurada a leitura continua aberta -- e o comportamento da
 * instancia publica, que e a demonstracao. Este teste existe para que ligar a
 * protecao na privada nunca feche a publica por acidente.
 */
@WebMvcTest(SistemasController.class)
@Import({ConfiguracaoCors.class, ConfiguracaoDeLeitura.class})
@TestPropertySource(properties = "sentinela.origens-permitidas=http://localhost:5500")
class LeituraAbertaTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsultaDeDisponibilidade consulta;

    @Test
    @DisplayName("sem chave configurada, a leitura nao exige cabecalho")
    void leituraAbertaSemChaveConfigurada() throws Exception {
        given(consulta.estadoDeTodos()).willReturn(List.of());

        mockMvc.perform(get("/sistemas")).andExpect(status().isOk());
    }
}

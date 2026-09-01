package com.sentinela.api.disponibilidade;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinela.api.erro.SistemaNaoEncontrado;
import com.sentinela.api.sistema.SistemaMonitorado;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Teste de fatia: sobe so a camada web -- controller, serializacao e tratamento
 * de erro -- com o servico substituido por um dublê. Nao ha Tomcat de verdade,
 * nao ha repositorio, e o contexto sobe em fracao do tempo de uma aplicacao
 * inteira.
 *
 * No Spring Boot 4 a anotacao @WebMvcTest mudou de pacote: veio de
 * spring-boot-webmvc-test, e nao mais do spring-boot-starter-test.
 */
@WebMvcTest(SistemasController.class)
class SistemasControllerTest {

    private static final SistemaMonitorado PORTAL = new SistemaMonitorado(
            "portal-servicos", "Portal de Servicos", URI.create("https://portal.example.com"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsultaDeDisponibilidade consulta;

    @Test
    @DisplayName("GET /sistemas lista os sistemas com o estado atual")
    void listaSistemasComEstadoAtual() throws Exception {
        Verificacao ultima = Verificacao.respondeuEm(
                "portal-servicos", Instant.parse("2026-08-30T12:00:00Z"), 142);
        given(consulta.estadoDeTodos()).willReturn(List.of(EstadoAtual.de(PORTAL, ultima)));

        mockMvc.perform(get("/sistemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("portal-servicos"))
                .andExpect(jsonPath("$[0].nome").value("Portal de Servicos"))
                .andExpect(jsonPath("$[0].situacao").value("NO_AR"))
                .andExpect(jsonPath("$[0].tempoRespostaMs").value(142));
    }

    @Test
    @DisplayName("sistema sem nenhuma verificacao aparece como SEM_DADO")
    void sistemaSemVerificacaoApareceComoSemDado() throws Exception {
        given(consulta.estadoDeTodos()).willReturn(List.of(EstadoAtual.semDado(PORTAL)));

        mockMvc.perform(get("/sistemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].situacao").value("SEM_DADO"))
                .andExpect(jsonPath("$[0].tempoRespostaMs").doesNotExist());
    }

    @Test
    @DisplayName("GET /sistemas/{id}/disponibilidade devolve a fita de 96 blocos")
    void devolveFitaDeDisponibilidade() throws Exception {
        Fita fita = CalculadoraDeDisponibilidade.fita(
                List.of(Verificacao.respondeuEm(
                        "portal-servicos", Instant.parse("2026-08-30T11:35:00Z"), 100)),
                Instant.parse("2026-08-30T12:00:00Z"));

        given(consulta.sistema("portal-servicos")).willReturn(PORTAL);
        given(consulta.fitaDe("portal-servicos")).willReturn(fita);

        mockMvc.perform(get("/sistemas/portal-servicos/disponibilidade"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sistemaId").value("portal-servicos"))
                .andExpect(jsonPath("$.janelaHoras").value(24))
                .andExpect(jsonPath("$.blocoMinutos").value(15))
                .andExpect(jsonPath("$.percentual").value(100.0))
                .andExpect(jsonPath("$.verificacoes").value(1))
                .andExpect(jsonPath("$.blocos.length()").value(96));
    }

    @Test
    @DisplayName("id desconhecido devolve 404 com codigo, e nao 500 com pilha")
    void sistemaDesconhecidoDevolve404() throws Exception {
        given(consulta.sistema("nao-existe")).willThrow(new SistemaNaoEncontrado("nao-existe"));

        mockMvc.perform(get("/sistemas/nao-existe/disponibilidade"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.codigo").value("sistema_nao_encontrado"))
                .andExpect(jsonPath("$.sistemaId").value("nao-existe"));
    }
}

package com.sentinela.api.eventos;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinela.api.erro.ChaveInvalida;
import com.sentinela.api.erro.EventoRecusado;
import com.sentinela.api.erro.LimiteExcedido;
import com.sentinela.api.eventos.ResumoDoDia.ResumoPorTipo;
import com.sentinela.api.sistema.SistemaMonitorado;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A camada web dos eventos: os codigos de status que cada recusa produz.
 */
@WebMvcTest(EventosController.class)
class EventosControllerTest {

    private static final String CHAVE = "X-Chave-Aplicacao";
    private static final SistemaMonitorado PORTAL = new SistemaMonitorado(
            "portal-servicos", "Portal de Servicos", URI.create("https://portal.example.com"));

    private static final String CORPO_VALIDO = """
            {"sistema":"portal-servicos","tipo":"senha.redefinida",
             "resultado":"sucesso","ocorridoEm":"2026-09-02T14:30:00Z"}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecepcaoDeEventos recepcao;

    @MockitoBean
    private ConsultaDeEventos consulta;

    @Test
    @DisplayName("evento aceito responde 201 e corpo vazio")
    void eventoAceitoResponde201() throws Exception {
        mockMvc.perform(post("/eventos")
                        .header(CHAVE, "chave-valida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_VALIDO))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("chave invalida responde 401")
    void chaveInvalidaResponde401() throws Exception {
        given(recepcao.receber(any(), any())).willThrow(new ChaveInvalida());

        mockMvc.perform(post("/eventos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_VALIDO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.codigo").value("chave_invalida"));
    }

    @Test
    @DisplayName("codigo fora da lista fechada responde 400 com o codigo da recusa")
    void tipoDesconhecidoResponde400() throws Exception {
        given(recepcao.receber(any(), any()))
                .willThrow(new EventoRecusado("tipo_desconhecido", "O tipo informado nao esta na lista."));

        mockMvc.perform(post("/eventos")
                        .header(CHAVE, "chave-valida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_VALIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("tipo_desconhecido"));
    }

    @Test
    @DisplayName("acima do limite responde 429")
    void limiteExcedidoResponde429() throws Exception {
        given(recepcao.receber(any(), any())).willThrow(new LimiteExcedido(500));

        mockMvc.perform(post("/eventos")
                        .header(CHAVE, "chave-valida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CORPO_VALIDO))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.codigo").value("limite_excedido"))
                .andExpect(jsonPath("$.limitePorHora").value(500));
    }

    @Test
    @DisplayName("campo que a API nao conhece derruba a requisicao, em vez de ser ignorado")
    void campoDesconhecidoResponde400() throws Exception {
        String comCampoAMais = """
                {"sistema":"portal-servicos","tipo":"senha.redefinida","resultado":"sucesso",
                 "ocorridoEm":"2026-09-02T14:30:00Z","usuario":"alguem"}""";

        mockMvc.perform(post("/eventos")
                        .header(CHAVE, "chave-valida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(comCampoAMais))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("corpo_ilegivel"));
    }

    @Test
    @DisplayName("a resposta de erro nao devolve o conteudo enviado")
    void respostaDeErroNaoEcoaOPayload() throws Exception {
        String comDadoPessoal = """
                {"sistema":"portal-servicos","tipo":"senha.redefinida","resultado":"sucesso",
                 "ocorridoEm":"2026-09-02T14:30:00Z","email":"joao.silva@exemplo.com"}""";

        String corpo = mockMvc.perform(post("/eventos")
                        .header(CHAVE, "chave-valida")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(comDadoPessoal))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(corpo)
                .doesNotContain("joao.silva")
                .doesNotContain("exemplo.com");
    }

    @Test
    @DisplayName("GET /sistemas/{id}/eventos devolve a contagem do dia e os ultimos eventos")
    void devolveResumoEUltimos() throws Exception {
        ResumoDoDia resumo = new ResumoDoDia(LocalDate.of(2026, 9, 2), 50, 47, 3,
                List.of(new ResumoPorTipo("senha.redefinida", 47, 3,
                        Map.of("dependencia_indisponivel", 3))));

        given(consulta.sistema("portal-servicos")).willReturn(PORTAL);
        given(consulta.resumoDeHoje("portal-servicos")).willReturn(resumo);
        given(consulta.ultimos("portal-servicos")).willReturn(List.of(
                new Evento("portal-servicos", "senha.redefinida", Resultado.FALHA,
                        "dependencia_indisponivel", Instant.parse("2026-09-02T14:20:11Z"), Map.of())));

        mockMvc.perform(get("/sistemas/portal-servicos/eventos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sistemaId").value("portal-servicos"))
                .andExpect(jsonPath("$.hoje.total").value(50))
                .andExpect(jsonPath("$.hoje.sucessos").value(47))
                .andExpect(jsonPath("$.hoje.falhas").value(3))
                .andExpect(jsonPath("$.hoje.tipos[0].tipo").value("senha.redefinida"))
                .andExpect(jsonPath("$.hoje.tipos[0].falhasPorMotivo.dependencia_indisponivel").value(3))
                .andExpect(jsonPath("$.ultimos[0].resultado").value("FALHA"))
                .andExpect(jsonPath("$.ultimos[0].motivo").value("dependencia_indisponivel"));
    }
}

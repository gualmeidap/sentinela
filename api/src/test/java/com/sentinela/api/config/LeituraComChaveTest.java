package com.sentinela.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinela.api.disponibilidade.ConsultaDeDisponibilidade;
import com.sentinela.api.disponibilidade.SistemasController;
import com.sentinela.api.erro.SistemaNaoEncontrado;
import com.sentinela.api.eventos.ConsultaDeEventos;
import com.sentinela.api.eventos.EventosController;
import com.sentinela.api.eventos.RecepcaoDeEventos;
import com.sentinela.api.ping.PingController;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A chave de leitura: o que ela barra, e o que ela nao pode barrar.
 *
 * O caso que mais importa aqui e o negativo. Uma chave que aceita qualquer
 * valor, ou que esquece um endpoint, nao quebra nada -- a tela continua
 * funcionando, e a API fica aberta sem que ninguem saiba.
 */
@WebMvcTest({SistemasController.class, EventosController.class, PingController.class})
@Import({ConfiguracaoCors.class, ConfiguracaoDeLeitura.class})
@TestPropertySource(properties = {
        "sentinela.origens-permitidas=http://localhost:5500",
        "sentinela.leitura.chave=" + LeituraComChaveTest.CHAVE,
        "sentinela.leitura.obrigatoria=true"})
class LeituraComChaveTest {

    static final String CHAVE = "chave-de-leitura-de-teste-0123456789";
    private static final String CABECALHO = LeituraComChave.CABECALHO;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsultaDeDisponibilidade disponibilidade;

    @MockitoBean
    private ConsultaDeEventos eventos;

    @MockitoBean
    private RecepcaoDeEventos recepcao;

    @Test
    @DisplayName("sem o cabecalho, toda leitura responde 401")
    void semChaveEBarrado() throws Exception {
        for (String caminho : List.of("/sistemas", "/sistemas/x/disponibilidade", "/sistemas/x/eventos")) {
            mockMvc.perform(get(caminho))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.codigo").value("chave_de_leitura_invalida"));
        }
    }

    @Test
    @DisplayName("chave errada responde 401, inclusive a que so difere por um caractere")
    void chaveErradaEBarrada() throws Exception {
        List<String> erradas = List.of(
                "",
                "outra-chave-qualquer",
                CHAVE.substring(0, CHAVE.length() - 1),
                CHAVE + "x",
                CHAVE.toUpperCase());
        for (String errada : erradas) {
            mockMvc.perform(get("/sistemas").header(CABECALHO, errada))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("a chave certa libera a leitura")
    void chaveCertaLibera() throws Exception {
        given(disponibilidade.estadoDeTodos()).willReturn(List.of());
        given(eventos.sistema("x")).willThrow(new SistemaNaoEncontrado("x"));

        mockMvc.perform(get("/sistemas").header(CABECALHO, CHAVE)).andExpect(status().isOk());
        // Chegar ao 404 do proprio controller prova que o interceptador deixou passar.
        mockMvc.perform(get("/sistemas/x/eventos").header(CABECALHO, CHAVE)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a resposta de recusa nunca devolve a chave, nem a que foi enviada")
    void recusaNaoEcoaChave() throws Exception {
        String enviada = "valor-enviado-que-nao-pode-voltar";

        String corpo = mockMvc.perform(get("/sistemas").header(CABECALHO, enviada))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(corpo).doesNotContain(enviada).doesNotContain(CHAVE);
    }

    @Test
    @DisplayName("caminho que nao existe tambem exige a chave: nem a existencia vaza")
    void caminhoDesconhecidoEBarrado() throws Exception {
        mockMvc.perform(get("/qualquer-coisa")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/ping segue aberto: o adaptador do Lambda chama sem chave antes de aceitar trafego")
    void pingNaoExigeChave() throws Exception {
        mockMvc.perform(get("/ping")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("publicar evento nao depende da chave de leitura: tem a chave propria da aplicacao")
    void publicarNaoExigeChaveDeLeitura() throws Exception {
        mockMvc.perform(post("/eventos")
                        .header("X-Chave-Aplicacao", "qualquer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("o pre-voo de CORS passa sem chave: o navegador nunca a envia nele")
    void preVooNaoExigeChave() throws Exception {
        mockMvc.perform(options("/sistemas")
                        .header("Origin", "http://localhost:5500")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"));
    }
}

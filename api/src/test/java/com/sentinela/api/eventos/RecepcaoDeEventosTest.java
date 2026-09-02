package com.sentinela.api.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinela.api.config.AplicacaoPublicadora;
import com.sentinela.api.config.PropriedadesDoSentinela;
import com.sentinela.api.erro.ChaveInvalida;
import com.sentinela.api.erro.EventoRecusado;
import com.sentinela.api.erro.LimiteExcedido;
import com.sentinela.api.erro.PublicacaoNaoAutorizada;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A porta de entrada dos eventos: o que ela aceita e, principalmente, o que ela
 * recusa.
 *
 * Estes testes sao a rede de seguranca da regra inegociavel do projeto. Um
 * afrouxamento aqui nao quebra nada visivelmente -- so passa a deixar entrar o
 * que nao deveria, em silencio.
 */
class RecepcaoDeEventosTest {

    private static final String CHAVE = "chave-secreta-de-teste";
    private static final String SISTEMA = "portal-servicos";
    private static final Instant AGORA = Instant.parse("2026-09-02T14:30:00Z");

    private RepositorioDeEventos repositorio;
    private RecepcaoDeEventos recepcao;

    @BeforeEach
    void montar() {
        AplicacaoPublicadora aplicacao = new AplicacaoPublicadora(
                SISTEMA, CHAVE, 3,
                List.of("senha.redefinida"),
                List.of("dependencia_indisponivel"),
                List.of("campus"));
        PropriedadesDoSentinela propriedades =
                new PropriedadesDoSentinela(List.of(), List.of(aplicacao), "America/Sao_Paulo");

        Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
        repositorio = new RepositorioDeEventosEmMemoria();
        recepcao = new RecepcaoDeEventos(
                new CatalogoDeAplicacoes(propriedades),
                new LimitadorDeEscrita(relogio),
                repositorio,
                relogio);
    }

    private EventoRecebido valido() {
        return new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso", null, AGORA, null);
    }

    private void esperarRecusa(EventoRecebido evento, String codigo) {
        assertThatThrownBy(() -> recepcao.receber(CHAVE, evento))
                .isInstanceOfSatisfying(EventoRecusado.class,
                        recusa -> assertThat(recusa.codigo()).isEqualTo(codigo));
    }

    @Test
    @DisplayName("evento valido e gravado")
    void eventoValidoEGravado() {
        recepcao.receber(CHAVE, valido());

        List<Evento> gravados = repositorio.ultimos(SISTEMA, 10);
        assertThat(gravados).hasSize(1);
        assertThat(gravados.get(0).tipo()).isEqualTo("senha.redefinida");
        assertThat(gravados.get(0).resultado()).isEqualTo(Resultado.SUCESSO);
    }

    @Test
    @DisplayName("sem chave, nada entra")
    void semChaveNaoEntra() {
        assertThatThrownBy(() -> recepcao.receber(null, valido())).isInstanceOf(ChaveInvalida.class);
        assertThatThrownBy(() -> recepcao.receber("  ", valido())).isInstanceOf(ChaveInvalida.class);
        assertThatThrownBy(() -> recepcao.receber("chave-errada", valido()))
                .isInstanceOf(ChaveInvalida.class);
    }

    @Test
    @DisplayName("uma chave publica pelo proprio sistema e por mais nenhum")
    void chaveNaoPublicaPorOutroSistema() {
        EventoRecebido deOutro = new EventoRecebido(
                "api-integracao", "senha.redefinida", "sucesso", null, AGORA, null);

        assertThatThrownBy(() -> recepcao.receber(CHAVE, deOutro))
                .isInstanceOf(PublicacaoNaoAutorizada.class);
    }

    @Test
    @DisplayName("tipo fora da lista fechada e recusado")
    void tipoForaDaListaERecusado() {
        esperarRecusa(new EventoRecebido(SISTEMA, "coisa.inventada", "sucesso", null, AGORA, null),
                "tipo_desconhecido");
    }

    @Test
    @DisplayName("motivo fora da lista fechada e recusado")
    void motivoForaDaListaERecusado() {
        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "falha",
                        "java.lang.NullPointerException em UsuarioService", AGORA, null),
                "motivo_desconhecido");
    }

    @Test
    @DisplayName("falha sem motivo e recusada")
    void falhaSemMotivoERecusada() {
        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "falha", null, AGORA, null),
                "motivo_ausente");
    }

    @Test
    @DisplayName("sucesso com motivo e recusado")
    void sucessoComMotivoERecusado() {
        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso",
                        "dependencia_indisponivel", AGORA, null),
                "motivo_em_sucesso");
    }

    @Test
    @DisplayName("resultado que nao e sucesso nem falha e recusado")
    void resultadoInvalidoERecusado() {
        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "parcial", null, AGORA, null),
                "resultado_invalido");
    }

    @Test
    @DisplayName("evento no futuro e recusado")
    void eventoNoFuturoERecusado() {
        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso", null,
                        AGORA.plus(Duration.ofHours(2)), null),
                "ocorrido_em_no_futuro");
    }

    @Test
    @DisplayName("evento mais antigo que a retencao e recusado")
    void eventoAntigoDemaisERecusado() {
        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso", null,
                        AGORA.minus(Duration.ofDays(31)), null),
                "ocorrido_em_muito_antigo");
    }

    @Test
    @DisplayName("pequeno adiantamento de relogio no publicador e tolerado")
    void toleraRelogioLevementeAdiantado() {
        recepcao.receber(CHAVE, new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso", null,
                AGORA.plus(Duration.ofMinutes(2)), null));

        assertThat(repositorio.ultimos(SISTEMA, 10)).hasSize(1);
    }

    @Test
    @DisplayName("campo de contexto nao declarado e recusado")
    void contextoNaoDeclaradoERecusado() {
        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso", null, AGORA,
                        Map.of("matricula", "20231234")),
                "contexto_nao_declarado");
    }

    @Test
    @DisplayName("valor de contexto em texto livre e recusado -- e o que barra dado pessoal")
    void contextoComTextoLivreERecusado() {
        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso", null, AGORA,
                        Map.of("campus", "joao.silva@exemplo.com")),
                "contexto_invalido");

        esperarRecusa(new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso", null, AGORA,
                        Map.of("campus", "Joao da Silva")),
                "contexto_invalido");
    }

    @Test
    @DisplayName("contexto declarado, em formato de codigo, e aceito")
    void contextoValidoEAceito() {
        recepcao.receber(CHAVE, new EventoRecebido(SISTEMA, "senha.redefinida", "sucesso", null,
                AGORA, Map.of("campus", "unidade_2")));

        assertThat(repositorio.ultimos(SISTEMA, 10).get(0).contexto())
                .containsEntry("campus", "unidade_2");
    }

    @Test
    @DisplayName("passando do limite, a aplicacao e silenciada")
    void limiteDeEscritaSilenciaAAplicacao() {
        recepcao.receber(CHAVE, valido());
        recepcao.receber(CHAVE, valido());
        recepcao.receber(CHAVE, valido());

        assertThatThrownBy(() -> recepcao.receber(CHAVE, valido()))
                .isInstanceOf(LimiteExcedido.class);

        assertThat(repositorio.ultimos(SISTEMA, 10)).hasSize(3);
    }

    @Test
    @DisplayName("evento invalido nao consome a cota do limite")
    void eventoInvalidoNaoConsomeCota() {
        esperarRecusa(new EventoRecebido(SISTEMA, "coisa.inventada", "sucesso", null, AGORA, null),
                "tipo_desconhecido");

        recepcao.receber(CHAVE, valido());
        recepcao.receber(CHAVE, valido());
        recepcao.receber(CHAVE, valido());

        assertThat(repositorio.ultimos(SISTEMA, 10)).hasSize(3);
    }
}

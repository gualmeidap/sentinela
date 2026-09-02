package com.sentinela.api.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinela.api.eventos.ResumoDoDia.ResumoPorTipo;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A agregacao que vira o "47 sucesso, 3 falha" da tela.
 *
 * JUnit puro: entra lista de evento, sai resumo.
 */
class AgregadorDeEventosTest {

    private static final String SISTEMA = "portal-servicos";
    private static final LocalDate DIA = LocalDate.of(2026, 9, 2);
    private static final Instant MOMENTO = Instant.parse("2026-09-02T12:00:00Z");

    private Evento sucesso(String tipo) {
        return new Evento(SISTEMA, tipo, Resultado.SUCESSO, null, MOMENTO, Map.of());
    }

    private Evento falha(String tipo, String motivo) {
        return new Evento(SISTEMA, tipo, Resultado.FALHA, motivo, MOMENTO, Map.of());
    }

    @Test
    @DisplayName("sem evento nenhum, o resumo vem zerado e nao nulo")
    void resumoVazio() {
        ResumoDoDia resumo = AgregadorDeEventos.resumir(List.of(), DIA);

        assertThat(resumo.total()).isZero();
        assertThat(resumo.sucessos()).isZero();
        assertThat(resumo.falhas()).isZero();
        assertThat(resumo.tipos()).isEmpty();
        assertThat(resumo.dia()).isEqualTo(DIA);
    }

    @Test
    @DisplayName("conta sucesso e falha separados, por tipo")
    void contaPorTipoEResultado() {
        ResumoDoDia resumo = AgregadorDeEventos.resumir(List.of(
                sucesso("senha.redefinida"),
                sucesso("senha.redefinida"),
                falha("senha.redefinida", "dependencia_indisponivel"),
                sucesso("conta.desbloqueada")), DIA);

        assertThat(resumo.total()).isEqualTo(4);
        assertThat(resumo.sucessos()).isEqualTo(3);
        assertThat(resumo.falhas()).isEqualTo(1);

        ResumoPorTipo senha = resumo.tipos().stream()
                .filter(t -> t.tipo().equals("senha.redefinida")).findFirst().orElseThrow();
        assertThat(senha.sucessos()).isEqualTo(2);
        assertThat(senha.falhas()).isEqualTo(1);
        assertThat(senha.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("falha agrupada por motivo -- a informacao util nao e '3 falhas'")
    void agrupaFalhaPorMotivo() {
        ResumoDoDia resumo = AgregadorDeEventos.resumir(List.of(
                falha("senha.redefinida", "dependencia_indisponivel"),
                falha("senha.redefinida", "dependencia_indisponivel"),
                falha("senha.redefinida", "dependencia_indisponivel"),
                falha("senha.redefinida", "credencial_invalida")), DIA);

        Map<String, Integer> motivos = resumo.tipos().get(0).falhasPorMotivo();
        assertThat(motivos)
                .containsEntry("dependencia_indisponivel", 3)
                .containsEntry("credencial_invalida", 1);
    }

    @Test
    @DisplayName("o motivo mais frequente vem primeiro")
    void motivoMaisFrequentePrimeiro() {
        ResumoDoDia resumo = AgregadorDeEventos.resumir(List.of(
                falha("senha.redefinida", "credencial_invalida"),
                falha("senha.redefinida", "dependencia_indisponivel"),
                falha("senha.redefinida", "dependencia_indisponivel")), DIA);

        assertThat(resumo.tipos().get(0).falhasPorMotivo().keySet())
                .containsExactly("dependencia_indisponivel", "credencial_invalida");
    }

    @Test
    @DisplayName("os tipos saem em ordem estavel, para a tela nao reordenar sozinha")
    void tiposEmOrdemEstavel() {
        ResumoDoDia resumo = AgregadorDeEventos.resumir(List.of(
                sucesso("senha.redefinida"),
                sucesso("conta.desbloqueada"),
                sucesso("conta.desbloqueada")), DIA);

        assertThat(resumo.tipos()).extracting(ResumoPorTipo::tipo)
                .containsExactly("conta.desbloqueada", "senha.redefinida");
    }

    @Test
    @DisplayName("sucesso nao entra na quebra por motivo")
    void sucessoNaoGeraMotivo() {
        ResumoDoDia resumo = AgregadorDeEventos.resumir(List.of(
                sucesso("senha.redefinida"),
                falha("senha.redefinida", "tempo_esgotado")), DIA);

        assertThat(resumo.tipos().get(0).falhasPorMotivo()).hasSize(1).containsEntry("tempo_esgotado", 1);
    }
}

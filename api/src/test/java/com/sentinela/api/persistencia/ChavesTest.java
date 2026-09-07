package com.sentinela.api.persistencia;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A chave de ordenacao do DynamoDB e comparada como texto. Estes testes
 * garantem que ordem alfabetica e ordem cronologica sao a mesma coisa -- e o
 * primeiro deles demonstra por que isso nao sai de graca.
 */
class ChavesTest {

    private static final Instant REDONDO = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant COM_MILISSEGUNDOS = Instant.parse("2026-09-01T10:00:00.500Z");

    @Test
    @DisplayName("o toString() do Java quebraria a ordenacao -- e por isso que existe formato proprio")
    void demonstraAArmadilhaDoToString() {
        // O Java omite os milissegundos quando sao zero.
        assertThat(REDONDO.toString()).isEqualTo("2026-09-01T10:00:00Z");
        assertThat(COM_MILISSEGUNDOS.toString()).isEqualTo("2026-09-01T10:00:00.500Z");

        // Cronologicamente REDONDO vem antes. Em texto, vem depois: o '.' tem
        // codigo menor que o 'Z'. Guardar assim entregaria a fita fora de ordem.
        assertThat(REDONDO).isBefore(COM_MILISSEGUNDOS);
        assertThat(REDONDO.toString().compareTo(COM_MILISSEGUNDOS.toString())).isPositive();

        // Com largura fixa, as duas ordens coincidem.
        assertThat(Chaves.deInstante(REDONDO).compareTo(Chaves.deInstante(COM_MILISSEGUNDOS)))
                .isNegative();
    }

    @Test
    @DisplayName("a chave tem largura fixa de 24 caracteres, sempre")
    void larguraFixa() {
        assertThat(Chaves.deInstante(REDONDO)).isEqualTo("2026-09-01T10:00:00.000Z").hasSize(24);
        assertThat(Chaves.deInstante(COM_MILISSEGUNDOS)).isEqualTo("2026-09-01T10:00:00.500Z").hasSize(24);
        assertThat(Chaves.deInstante(Instant.parse("2026-01-02T03:04:05.007Z"))).hasSize(24);
    }

    @Test
    @DisplayName("ordenar as chaves como texto devolve a ordem cronologica")
    void ordemAlfabeticaEhOrdemCronologica() {
        List<Instant> momentos = List.of(
                Instant.parse("2026-09-01T23:59:59.999Z"),
                Instant.parse("2026-09-01T10:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-01T10:00:00.001Z"));

        List<String> ordenadas = momentos.stream().map(Chaves::deInstante).sorted().toList();

        assertThat(ordenadas).containsExactly(
                "2026-09-01T10:00:00.000Z",
                "2026-09-01T10:00:00.001Z",
                "2026-09-01T23:59:59.999Z",
                "2026-09-02T00:00:00.000Z");
    }

    @Test
    @DisplayName("a chave volta a ser instante sem perder informacao")
    void idaEVolta() {
        assertThat(Chaves.paraInstante(Chaves.deInstante(COM_MILISSEGUNDOS))).isEqualTo(COM_MILISSEGUNDOS);
        assertThat(Chaves.paraInstante(Chaves.deInstante(REDONDO))).isEqualTo(REDONDO);
    }

    @Test
    @DisplayName("dois itens no mesmo instante ganham chaves diferentes, e um nao apaga o outro")
    void chaveCompostaDesempata() {
        String primeira = Chaves.deInstanteComId(REDONDO, "aaa");
        String segunda = Chaves.deInstanteComId(REDONDO, "bbb");

        assertThat(primeira).isNotEqualTo(segunda);
        assertThat(primeira).startsWith("2026-09-01T10:00:00.000Z#");
        assertThat(primeira.compareTo(segunda)).isNegative();
    }

    @Test
    @DisplayName("a faixa [inicio, fim) inclui todo o dia e exclui a virada")
    void faixaDoDiaEhSemiaberta() {
        String inicio = Chaves.inicioDaFaixa(Instant.parse("2026-09-01T00:00:00Z"));
        String fim = Chaves.fimDaFaixaExclusivo(Instant.parse("2026-09-02T00:00:00Z"));

        String primeiroDoDia = Chaves.deInstanteComId(Instant.parse("2026-09-01T00:00:00Z"), "x");
        String ultimoDoDia = Chaves.deInstanteComId(Instant.parse("2026-09-01T23:59:59.999Z"), "x");
        String jaEhOutroDia = Chaves.deInstanteComId(Instant.parse("2026-09-02T00:00:00Z"), "x");
        String vespera = Chaves.deInstanteComId(Instant.parse("2026-08-31T23:59:59.999Z"), "x");

        assertThat(dentro(primeiroDoDia, inicio, fim)).isTrue();
        assertThat(dentro(ultimoDoDia, inicio, fim)).isTrue();
        assertThat(dentro(jaEhOutroDia, inicio, fim)).isFalse();
        assertThat(dentro(vespera, inicio, fim)).isFalse();
    }

    @Test
    @DisplayName("na chave simples, a faixa tambem exclui o instante final")
    void faixaSimplesTambemEhSemiaberta() {
        String inicio = Chaves.deInstante(Instant.parse("2026-09-01T00:00:00Z"));
        String fim = Chaves.fimSimplesExclusivo(Instant.parse("2026-09-02T00:00:00Z"));

        String primeiro = Chaves.deInstante(Instant.parse("2026-09-01T00:00:00Z"));
        String ultimo = Chaves.deInstante(Instant.parse("2026-09-01T23:59:59.999Z"));
        String jaEhOutroDia = Chaves.deInstante(Instant.parse("2026-09-02T00:00:00Z"));

        assertThat(dentro(primeiro, inicio, fim)).isTrue();
        assertThat(dentro(ultimo, inicio, fim)).isTrue();
        assertThat(dentro(jaEhOutroDia, inicio, fim)).isFalse();
    }

    /** Reproduz o "between" do DynamoDB, que e inclusivo nas duas pontas. */
    private boolean dentro(String chave, String inicio, String fim) {
        return chave.compareTo(inicio) >= 0 && chave.compareTo(fim) <= 0;
    }

    @Test
    @DisplayName("o TTL e o momento mais a retencao, em segundos desde 1970")
    void calculaOTtl() {
        long expira = Chaves.expiraEm(REDONDO, Duration.ofDays(30));

        assertThat(expira).isEqualTo(REDONDO.plus(Duration.ofDays(30)).getEpochSecond());
        assertThat(Instant.ofEpochSecond(expira)).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    }
}

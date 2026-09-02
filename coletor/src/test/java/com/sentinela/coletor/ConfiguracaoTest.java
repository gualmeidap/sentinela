package com.sentinela.coletor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * De onde vem a lista de alvos, e o que acontece quando ela vem errada.
 *
 * Configuracao malformada tem que derrubar a subida com mensagem clara, e nao
 * fazer o coletor rodar em silencio sem verificar nada.
 */
class ConfiguracaoTest {

    @TempDir
    Path pasta;

    @Test
    @DisplayName("le a lista do arquivo .properties passado como argumento")
    void leDoArquivo() throws IOException {
        Path arquivo = pasta.resolve("alvos.properties");
        Files.writeString(arquivo, """
                portal-servicos=https://portal.example.com
                api-integracao=https://api.example.com
                """);

        Configuracao configuracao = Configuracao.de(new String[] {arquivo.toString()}, Map.of());

        assertThat(configuracao.alvos()).extracting(Alvo::id)
                .containsExactly("api-integracao", "portal-servicos");
        assertThat(configuracao.tempoLimite()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("le a lista da variavel de ambiente, que e o que a Lambda tem")
    void leDaVariavelDeAmbiente() {
        Configuracao configuracao = Configuracao.de(new String[0], Map.of(
                Configuracao.VARIAVEL_DE_ALVOS,
                "portal-servicos=https://portal.example.com;agendamento=https://agendamento.example.com"));

        assertThat(configuracao.alvos()).extracting(Alvo::id)
                .containsExactly("portal-servicos", "agendamento");
    }

    @Test
    @DisplayName("tempo limite vem da variavel de ambiente quando definido")
    void tempoLimiteConfiguravel() {
        Configuracao configuracao = Configuracao.de(new String[0], Map.of(
                Configuracao.VARIAVEL_DE_ALVOS, "portal-servicos=https://portal.example.com",
                Configuracao.VARIAVEL_DE_TEMPO_LIMITE, "1500"));

        assertThat(configuracao.tempoLimite()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    @DisplayName("sem alvo nenhum, o coletor recusa subir em vez de rodar a toa")
    void semAlvoRecusa() {
        assertThatThrownBy(() -> Configuracao.de(new String[0], Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nenhum alvo configurado");
    }

    @Test
    @DisplayName("arquivo inexistente e recusado com mensagem clara")
    void arquivoInexistenteERecusado() {
        assertThatThrownBy(() -> Configuracao.de(new String[] {pasta.resolve("nao-existe").toString()}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("arquivo de alvos nao encontrado");
    }

    @Test
    @DisplayName("url que nao e http nem https e recusada")
    void esquemaInvalidoERecusado() {
        assertThatThrownBy(() -> Configuracao.deTexto("banco=jdbc:postgresql://localhost/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http");
    }

    @Test
    @DisplayName("linha fora do formato id=url e recusada")
    void formatoInvalidoERecusado() {
        assertThatThrownBy(() -> Configuracao.deTexto("so-o-id-sem-url"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id=url");
    }

    @Test
    @DisplayName("tempo limite que nao e numero e recusado")
    void tempoLimiteInvalidoERecusado() {
        assertThatThrownBy(() -> Configuracao.de(new String[0], Map.of(
                Configuracao.VARIAVEL_DE_ALVOS, "portal-servicos=https://portal.example.com",
                Configuracao.VARIAVEL_DE_TEMPO_LIMITE, "cinco segundos")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("milissegundos");
    }

    @Test
    @DisplayName("a mensagem de erro cita o id do alvo, nunca a url")
    void mensagemDeErroNaoVazaAUrl() {
        assertThatThrownBy(() -> Configuracao.deTexto("interno=ht tp://servidor-interno-secreto/x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interno")
                .hasMessageNotContaining("servidor-interno-secreto");
    }

    @Test
    @DisplayName("alvo sem id e recusado")
    void alvoSemIdERecusado() {
        assertThatThrownBy(() -> new Alvo("  ", java.net.URI.create("https://portal.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("lista vazia na variavel de ambiente cai no mesmo erro de 'sem alvo'")
    void variavelVaziaEquivaleASemAlvo() {
        assertThat(Configuracao.deTexto("")).isEqualTo(List.of());
        assertThat(Configuracao.deTexto(null)).isEqualTo(List.of());
    }
}

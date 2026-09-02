package com.sentinela.coletor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os quatro modos de falha que o briefing manda tratar explicitamente.
 *
 * O servidor de teste e o com.sun.net.httpserver, que vem no proprio JDK: da
 * para exercitar HTTP de verdade -- inclusive lentidao e erro -- sem
 * dependencia nova e sem depender da internet, que tornaria o teste lento e
 * intermitente.
 */
class VerificadorHttpTest {

    private static final Instant AGORA = Instant.parse("2026-09-02T12:00:00Z");
    private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);

    private HttpServer servidor;
    private int porta;

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        porta = servidor.getAddress().getPort();

        servidor.createContext("/ok", troca -> {
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        servidor.createContext("/quebrado", troca -> {
            troca.sendResponseHeaders(500, -1);
            troca.close();
        });
        servidor.createContext("/nao-encontrado", troca -> {
            troca.sendResponseHeaders(404, -1);
            troca.close();
        });
        servidor.createContext("/lento", troca -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException interrompido) {
                Thread.currentThread().interrupt();
            }
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        servidor.start();
    }

    @AfterEach
    void derrubarServidor() {
        servidor.stop(0);
    }

    private Alvo alvo(String caminho) {
        return new Alvo("alvo-de-teste", URI.create("http://127.0.0.1:" + porta + caminho));
    }

    private VerificadorHttp verificador(Duration tempoLimite) {
        return new VerificadorHttp(tempoLimite, RELOGIO);
    }

    @Test
    @DisplayName("alvo que responde 200 conta como no ar, com o tempo medido")
    void alvoNoAr() {
        Verificacao verificacao = verificador(Duration.ofSeconds(5)).verificar(alvo("/ok"));

        assertThat(verificacao.respondeu()).isTrue();
        assertThat(verificacao.motivo()).isNull();
        assertThat(verificacao.statusHttp()).isEqualTo(200);
        assertThat(verificacao.tempoRespostaMs()).isNotNegative();
        assertThat(verificacao.momento()).isEqualTo(AGORA);
    }

    @Test
    @DisplayName("alvo que demora demais conta como TEMPO_ESGOTADO, e nao trava a rodada")
    void alvoLentoDemais() {
        Verificacao verificacao = verificador(Duration.ofMillis(300)).verificar(alvo("/lento"));

        assertThat(verificacao.respondeu()).isFalse();
        assertThat(verificacao.motivo()).isEqualTo(MotivoDaFalha.TEMPO_ESGOTADO);
        assertThat(verificacao.statusHttp()).isNull();
    }

    @Test
    @DisplayName("servidor de pe mas com erro 500 conta como fora do ar")
    void alvoComErroDeServidor() {
        Verificacao verificacao = verificador(Duration.ofSeconds(5)).verificar(alvo("/quebrado"));

        assertThat(verificacao.respondeu()).isFalse();
        assertThat(verificacao.motivo()).isEqualTo(MotivoDaFalha.STATUS_DE_ERRO);
        assertThat(verificacao.statusHttp()).isEqualTo(500);
    }

    @Test
    @DisplayName("404 tambem conta como fora do ar: o endereco monitorado deixou de existir")
    void alvoComQuatrocentosEQuatro() {
        Verificacao verificacao = verificador(Duration.ofSeconds(5)).verificar(alvo("/nao-encontrado"));

        assertThat(verificacao.respondeu()).isFalse();
        assertThat(verificacao.motivo()).isEqualTo(MotivoDaFalha.STATUS_DE_ERRO);
        assertThat(verificacao.statusHttp()).isEqualTo(404);
    }

    @Test
    @DisplayName("porta fechada conta como CONEXAO_RECUSADA")
    void alvoComPortaFechada() throws IOException {
        int portaLivre;
        try (ServerSocket tomada = new ServerSocket(0)) {
            portaLivre = tomada.getLocalPort();
        }

        Verificacao verificacao = verificador(Duration.ofSeconds(2)).verificar(
                new Alvo("fora-do-ar", URI.create("http://127.0.0.1:" + portaLivre + "/")));

        assertThat(verificacao.respondeu()).isFalse();
        assertThat(verificacao.motivo()).isEqualTo(MotivoDaFalha.CONEXAO_RECUSADA);
    }

    @Test
    @DisplayName("host que nao existe conta como CONEXAO_RECUSADA, sem excecao vazando")
    void alvoComHostInexistente() {
        Verificacao verificacao = verificador(Duration.ofSeconds(3)).verificar(
                new Alvo("inexistente", URI.create("http://nao.existe.invalid/")));

        assertThat(verificacao.respondeu()).isFalse();
        assertThat(verificacao.motivo()).isEqualTo(MotivoDaFalha.CONEXAO_RECUSADA);
    }

    @Test
    @DisplayName("verificar() nunca lanca excecao, seja qual for o alvo")
    void nuncaLancaExcecao() {
        VerificadorHttp verificador = verificador(Duration.ofMillis(200));

        assertThat(verificador.verificar(alvo("/ok"))).isNotNull();
        assertThat(verificador.verificar(alvo("/lento"))).isNotNull();
        assertThat(verificador.verificar(alvo("/quebrado"))).isNotNull();
        assertThat(verificador.verificar(new Alvo("x", URI.create("http://nao.existe.invalid/")))).isNotNull();
    }
}

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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A rodada completa.
 *
 * O caso que mais importa: um alvo fora do ar nao pode apagar o resultado dos
 * outros. Numa rodada de dez sistemas, uma excecao nao tratada deixaria o
 * painel cego sobre os nove que estavam bem -- justamente no momento em que
 * alguem esta olhando para entender o que houve.
 */
class ColetorTest {

    private static final Instant AGORA = Instant.parse("2026-09-02T12:00:00Z");
    private static final Clock RELOGIO = Clock.fixed(AGORA, ZoneOffset.UTC);

    /** Guarda o que a rodada entregou, no lugar de um banco. */
    private static final class RegistroDeTeste implements RegistroDeVerificacoes {
        private final List<Verificacao> recebidas = new ArrayList<>();

        @Override
        public void registrar(List<Verificacao> verificacoes) {
            recebidas.addAll(verificacoes);
        }
    }

    private HttpServer servidor;
    private int porta;
    private int portaFechada;

    @BeforeEach
    void subirServidor() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        porta = servidor.getAddress().getPort();
        servidor.createContext("/ok", troca -> {
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        servidor.start();

        try (ServerSocket tomada = new ServerSocket(0)) {
            portaFechada = tomada.getLocalPort();
        }
    }

    @AfterEach
    void derrubarServidor() {
        servidor.stop(0);
    }

    private Coletor coletor(RegistroDeVerificacoes registro) {
        return new Coletor(new VerificadorHttp(Duration.ofSeconds(2), RELOGIO), registro, RELOGIO);
    }

    @Test
    @DisplayName("um alvo fora do ar nao impede os outros de serem verificados")
    void alvoForaNaoDerrubaARodada() {
        RegistroDeTeste registro = new RegistroDeTeste();

        List<Verificacao> resultados = coletor(registro).executar(List.of(
                new Alvo("no-ar-1", URI.create("http://127.0.0.1:" + porta + "/ok")),
                new Alvo("fora", URI.create("http://127.0.0.1:" + portaFechada + "/")),
                new Alvo("no-ar-2", URI.create("http://127.0.0.1:" + porta + "/ok"))));

        assertThat(resultados).hasSize(3);
        assertThat(resultados).extracting(Verificacao::sistemaId)
                .containsExactly("no-ar-1", "fora", "no-ar-2");
        assertThat(resultados).filteredOn(Verificacao::respondeu).hasSize(2);
        assertThat(registro.recebidas).hasSize(3);
    }

    @Test
    @DisplayName("a ordem do resultado acompanha a ordem dos alvos, apesar do paralelismo")
    void ordemDoResultadoAcompanhaAEntrada() {
        List<Alvo> alvos = List.of(
                new Alvo("c", URI.create("http://127.0.0.1:" + porta + "/ok")),
                new Alvo("a", URI.create("http://127.0.0.1:" + porta + "/ok")),
                new Alvo("b", URI.create("http://127.0.0.1:" + porta + "/ok")));

        assertThat(coletor(new RegistroDeTeste()).executar(alvos))
                .extracting(Verificacao::sistemaId)
                .containsExactly("c", "a", "b");
    }

    @Test
    @DisplayName("lista vazia nao chama o registro nem quebra")
    void listaVaziaNaoQuebra() {
        RegistroDeTeste registro = new RegistroDeTeste();

        assertThat(coletor(registro).executar(List.of())).isEmpty();
        assertThat(registro.recebidas).isEmpty();
    }

    @Test
    @DisplayName("os resultados chegam ao registro, que no passo 4 vira o DynamoDB")
    void entregaTudoAoRegistro() {
        RegistroDeTeste registro = new RegistroDeTeste();

        coletor(registro).executar(List.of(
                new Alvo("no-ar", URI.create("http://127.0.0.1:" + porta + "/ok"))));

        assertThat(registro.recebidas).hasSize(1);
        assertThat(registro.recebidas.get(0).respondeu()).isTrue();
        assertThat(registro.recebidas.get(0).momento()).isEqualTo(AGORA);
    }
}

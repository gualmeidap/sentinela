package com.sentinela.coletor.lambda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.sentinela.coletor.ApoioDynamoLocal;
import com.sentinela.coletor.ChavesDoBanco;
import com.sentinela.coletor.DestinoDynamo;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

/**
 * O ponto de entrada da Lambda.
 *
 * O caso que mais importa aqui e o do construtor: ele roda no cold start, e uma
 * configuracao faltando precisa derrubar a funcao com mensagem clara. Falhar em
 * silencio significaria uma funcao que dispara a cada 5 minutos sem gravar
 * nada, e ninguem descobre ate abrir o painel vazio.
 */
@EnabledIf("dynamoLocalNoAr")
class ManipuladorLambdaTest {

    private static final Clock RELOGIO = Clock.fixed(
            Instant.parse("2026-09-07T18:00:00Z"), ZoneOffset.UTC);

    private HttpServer servidor;
    private DestinoDynamo destino;
    private DynamoDbClient cliente;

    static boolean dynamoLocalNoAr() {
        return ApoioDynamoLocal.disponivel();
    }

    @BeforeEach
    void preparar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        servidor.createContext("/ok", troca -> {
            troca.sendResponseHeaders(200, -1);
            troca.close();
        });
        servidor.start();

        destino = ApoioDynamoLocal.destino();
        cliente = ApoioDynamoLocal.clienteComTabela(destino);
    }

    @AfterEach
    void encerrar() {
        servidor.stop(0);
        if (cliente != null) {
            cliente.close();
        }
    }

    private Map<String, String> ambienteCompleto() {
        return Map.of(
                "SENTINELA_ALVOS", "alvo-de-teste=http://127.0.0.1:"
                        + servidor.getAddress().getPort() + "/ok",
                DestinoDynamo.VARIAVEL_TABELA, destino.tabela(),
                DestinoDynamo.VARIAVEL_ENDPOINT, ApoioDynamoLocal.ENDPOINT,
                DestinoDynamo.VARIAVEL_REGIAO, "us-east-1");
    }

    @Test
    @DisplayName("uma invocacao verifica os alvos e grava a rodada")
    void invocacaoGravaARodada() {
        ManipuladorLambda manipulador = new ManipuladorLambda(ambienteCompleto(), RELOGIO);
        ContextoDeTeste contexto = new ContextoDeTeste();

        String resposta = manipulador.handleRequest(new Object(), contexto);

        assertThat(resposta).isEqualTo("1 alvo(s) verificado(s), 0 fora do ar");
        assertThat(contexto.registrado).containsExactly("1 alvo(s) verificado(s), 0 fora do ar");

        var itens = cliente.scan(ScanRequest.builder().tableName(destino.tabela()).build()).items();
        assertThat(itens).hasSize(1);
        assertThat(itens.get(0).get(ChavesDoBanco.PARTICAO).s()).isEqualTo("alvo-de-teste");
        assertThat(itens.get(0).get(ChavesDoBanco.RESPONDEU).bool()).isTrue();
    }

    @Test
    @DisplayName("o cliente e montado uma vez e sobrevive a varias invocacoes")
    void mesmaInstanciaAtendeVariasInvocacoes() {
        ManipuladorLambda manipulador = new ManipuladorLambda(ambienteCompleto(), RELOGIO);
        ContextoDeTeste contexto = new ContextoDeTeste();

        manipulador.handleRequest(new Object(), contexto);
        manipulador.handleRequest(new Object(), contexto);
        manipulador.handleRequest(new Object(), contexto);

        // Mesmo relogio fixo em todas: a chave e a mesma e a escrita sobrescreve.
        // O que se verifica aqui e que a segunda e a terceira invocacao nao
        // quebram por reusar o cliente montado no construtor.
        assertThat(cliente.scan(ScanRequest.builder().tableName(destino.tabela()).build()).items())
                .hasSize(1);
    }

    @Test
    @DisplayName("sem a variavel da tabela, a funcao morre no cold start com mensagem clara")
    void semTabelaNaoSobe() {
        Map<String, String> semTabela = Map.of(
                "SENTINELA_ALVOS", "alvo=http://127.0.0.1:1/");

        assertThatThrownBy(() -> new ManipuladorLambda(semTabela, RELOGIO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DestinoDynamo.VARIAVEL_TABELA);
    }

    @Test
    @DisplayName("sem lista de alvos, a funcao tambem nao sobe")
    void semAlvosNaoSobe() {
        Map<String, String> semAlvos = Map.of(
                DestinoDynamo.VARIAVEL_TABELA, destino.tabela(),
                DestinoDynamo.VARIAVEL_ENDPOINT, ApoioDynamoLocal.ENDPOINT);

        assertThatThrownBy(() -> new ManipuladorLambda(semAlvos, RELOGIO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nenhum alvo configurado");
    }

    /** Context e uma interface grande; aqui so o logger e usado. */
    private static final class ContextoDeTeste implements Context {
        private final List<String> registrado = new ArrayList<>();

        @Override
        public LambdaLogger getLogger() {
            return new LambdaLogger() {
                @Override
                public void log(String mensagem) {
                    registrado.add(mensagem);
                }

                @Override
                public void log(byte[] mensagem) {
                    registrado.add(new String(mensagem));
                }
            };
        }

        @Override public String getAwsRequestId() { return "teste"; }
        @Override public String getLogGroupName() { return "teste"; }
        @Override public String getLogStreamName() { return "teste"; }
        @Override public String getFunctionName() { return "sentinela-coletor"; }
        @Override public String getFunctionVersion() { return "1"; }
        @Override public String getInvokedFunctionArn() { return "arn:teste"; }
        @Override public CognitoIdentity getIdentity() { return null; }
        @Override public ClientContext getClientContext() { return null; }
        @Override public int getRemainingTimeInMillis() { return 30000; }
        @Override public int getMemoryLimitInMB() { return 512; }
    }
}

package com.sentinela.coletor;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Faz uma chamada ao alvo e transforma o que acontecer numa Verificacao.
 *
 * O metodo verificar() nao lanca excecao. Nunca. Alvo fora do ar e resultado
 * esperado deste programa, nao acidente -- se cada queda virasse excecao, a
 * rodada inteira morreria por causa de um alvo e o portal ficaria cego
 * justamente quando havia algo para mostrar.
 */
public class VerificadorHttp {

    private final HttpClient cliente;
    private final Duration tempoLimite;
    private final Clock relogio;

    public VerificadorHttp(Duration tempoLimite, Clock relogio) {
        this.tempoLimite = tempoLimite;
        this.relogio = relogio;
        this.cliente = HttpClient.newBuilder()
                .connectTimeout(tempoLimite)
                // Redirecionamento nao e seguido: um 301 para outro endereco
                // significa que este endereco nao serve mais o sistema, e isso
                // e informacao, nao detalhe a esconder.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Verificacao verificar(Alvo alvo) {
        Instant momento = relogio.instant();
        long comeco = System.nanoTime();

        try {
            HttpRequest requisicao = HttpRequest.newBuilder(alvo.url())
                    .timeout(tempoLimite)
                    .header("User-Agent", "sentinela-coletor")
                    .GET()
                    .build();

            // discarding() joga o corpo fora conforme chega: o coletor quer
            // saber se respondeu e em quanto tempo, nao o que veio. Guardar o
            // corpo gastaria memoria da Lambda a toa.
            HttpResponse<Void> resposta = cliente.send(requisicao, HttpResponse.BodyHandlers.discarding());
            int decorrido = decorridoMs(comeco);

            if (resposta.statusCode() >= 400) {
                return Verificacao.falhou(alvo.id(), momento, decorrido,
                        MotivoDaFalha.STATUS_DE_ERRO, resposta.statusCode());
            }
            return Verificacao.respondeu(alvo.id(), momento, decorrido, resposta.statusCode());

        } catch (HttpTimeoutException demorouDemais) {
            return Verificacao.falhou(alvo.id(), momento, decorridoMs(comeco),
                    MotivoDaFalha.TEMPO_ESGOTADO, null);

        } catch (ConnectException | UnknownHostException naoAbriu) {
            return Verificacao.falhou(alvo.id(), momento, decorridoMs(comeco),
                    MotivoDaFalha.CONEXAO_RECUSADA, null);

        } catch (IOException outraFalhaDeRede) {
            return Verificacao.falhou(alvo.id(), momento, decorridoMs(comeco),
                    MotivoDaFalha.ERRO_INESPERADO, null);

        } catch (InterruptedException interrompido) {
            // Restaurar a flag e obrigatorio: engolir a interrupcao faria a
            // thread ignorar um pedido de parada, e na Lambda isso vira funcao
            // que nao termina no tempo.
            Thread.currentThread().interrupt();
            return Verificacao.falhou(alvo.id(), momento, decorridoMs(comeco),
                    MotivoDaFalha.ERRO_INESPERADO, null);
        }
    }

    /**
     * nanoTime, e nao Instant.now() duas vezes: o relogio de parede pode ser
     * ajustado no meio da medicao (NTP, horario de verao) e devolver duracao
     * negativa. nanoTime so anda para a frente.
     */
    private int decorridoMs(long comecoEmNanos) {
        long decorrido = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - comecoEmNanos);
        return (int) Math.min(decorrido, Integer.MAX_VALUE);
    }
}

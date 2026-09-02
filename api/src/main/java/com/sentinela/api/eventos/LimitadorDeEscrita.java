package com.sentinela.api.eventos;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Limite de escrita por aplicacao.
 *
 * O cenario que isto evita nao e ataque, e bug: um laco que publica dentro de um
 * retry, um reprocessamento disparado duas vezes. Sem teto, uma aplicacao com
 * defeito enche o banco e derruba o portal para todo mundo -- inclusive para os
 * sistemas que estavam se comportando.
 *
 * A janela e a hora do relogio: das 14:00 as 15:00 vale um teto, e as 15:00 a
 * contagem zera. E mais grosseiro que uma janela deslizante, mas e trivial de
 * explicar, de testar e de conferir num log -- e o silenciamento se desfaz
 * sozinho, sem ninguem precisar destravar nada na madrugada.
 *
 * O silenciamento e registrado uma vez por janela. Um sistema em laco geraria
 * milhares de linhas identicas, e o log viraria parte do problema.
 */
@Component
public class LimitadorDeEscrita {

    private static final Logger log = LoggerFactory.getLogger(LimitadorDeEscrita.class);

    private final Clock relogio;
    private final Map<String, Contagem> porAplicacao = new ConcurrentHashMap<>();

    public LimitadorDeEscrita(Clock relogio) {
        this.relogio = relogio;
    }

    /**
     * @return true se o evento cabe no limite; false se a aplicacao esta silenciada.
     */
    public boolean cabeNoLimite(String aplicacaoId, int limitePorHora) {
        Instant agora = relogio.instant();
        Instant janelaAtual = agora.truncatedTo(ChronoUnit.HOURS);
        boolean[] aceito = {false};

        // compute() roda de forma atomica para a chave: duas requisicoes
        // simultaneas da mesma aplicacao nao conseguem ler a mesma contagem e
        // gravar por cima uma da outra.
        porAplicacao.compute(aplicacaoId, (id, atual) -> {
            Contagem contagem = (atual == null || atual.janela.isBefore(janelaAtual))
                    ? new Contagem(janelaAtual)
                    : atual;

            if (contagem.total >= limitePorHora) {
                if (contagem.silenciadaDesde == null) {
                    contagem.silenciadaDesde = agora;
                    log.warn("aplicacao {} silenciada ate o fim da hora: passou de {} eventos na janela",
                            id, limitePorHora);
                }
                aceito[0] = false;
            } else {
                contagem.total++;
                aceito[0] = true;
            }
            return contagem;
        });

        return aceito[0];
    }

    /** Desde quando a aplicacao esta silenciada, se estiver. */
    public Optional<Instant> silenciadaDesde(String aplicacaoId) {
        Contagem contagem = porAplicacao.get(aplicacaoId);
        if (contagem == null || contagem.silenciadaDesde == null) {
            return Optional.empty();
        }
        if (contagem.janela.isBefore(relogio.instant().truncatedTo(ChronoUnit.HOURS))) {
            return Optional.empty();
        }
        return Optional.of(contagem.silenciadaDesde);
    }

    private static final class Contagem {
        private final Instant janela;
        private int total;
        private Instant silenciadaDesde;

        private Contagem(Instant janela) {
            this.janela = janela;
        }
    }
}

package com.sentinela.api.disponibilidade;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * As regras de negocio da disponibilidade.
 *
 * Sem Spring, sem banco, sem HTTP: entra lista de verificacao, sai fita. E o
 * que permite testar esta classe com JUnit puro, em milissegundos, sem subir
 * contexto nenhum -- e e onde mora o risco de erro de verdade do passo 1.
 */
public final class CalculadoraDeDisponibilidade {

    public static final Duration JANELA_PADRAO = Duration.ofHours(24);
    public static final Duration BLOCO_PADRAO = Duration.ofMinutes(15);

    private CalculadoraDeDisponibilidade() {
    }

    public static Fita fita(List<Verificacao> verificacoes, Instant fim) {
        return fita(verificacoes, fim, JANELA_PADRAO, BLOCO_PADRAO);
    }

    /**
     * Divide a janela em blocos de tamanho fixo e resume cada um.
     *
     * A fita termina no limite de bloco seguinte a "fim", e nao em "fim" cru.
     * Sem esse alinhamento, cada requisicao devolveria blocos comecando num
     * segundo diferente e a fita tremeria na tela a cada atualizacao. O efeito
     * colateral desejado e que o bloco em andamento aparece na ponta direita.
     *
     * Verificacao fora da janela e ignorada em silencio: a fita e um recorte,
     * nao uma validacao da entrada.
     */
    public static Fita fita(List<Verificacao> verificacoes, Instant fim, Duration janela, Duration bloco) {
        if (janela.isZero() || janela.isNegative() || bloco.isZero() || bloco.isNegative()) {
            throw new IllegalArgumentException("janela e bloco precisam ser positivos");
        }
        if (janela.getSeconds() % bloco.getSeconds() != 0) {
            throw new IllegalArgumentException("a janela precisa ser multipla do bloco");
        }

        long segundosPorBloco = bloco.getSeconds();
        long limite = Math.floorDiv(fim.getEpochSecond(), segundosPorBloco) * segundosPorBloco + segundosPorBloco;
        int quantidade = (int) (janela.getSeconds() / segundosPorBloco);

        Instant inicioDaFita = Instant.ofEpochSecond(limite - janela.getSeconds());
        Instant fimDaFita = Instant.ofEpochSecond(limite);

        int[] totalPorBloco = new int[quantidade];
        int[] falhasPorBloco = new int[quantidade];

        for (Verificacao verificacao : verificacoes) {
            long deslocamento = verificacao.momento().getEpochSecond() - inicioDaFita.getEpochSecond();
            if (deslocamento < 0) {
                continue;
            }
            int indice = (int) (deslocamento / segundosPorBloco);
            if (indice >= quantidade) {
                continue;
            }
            totalPorBloco[indice]++;
            if (!verificacao.respondeu()) {
                falhasPorBloco[indice]++;
            }
        }

        List<Bloco> blocos = new ArrayList<>(quantidade);
        int total = 0;
        int falhas = 0;
        for (int i = 0; i < quantidade; i++) {
            Instant inicioDoBloco = inicioDaFita.plusSeconds((long) i * segundosPorBloco);
            blocos.add(Bloco.de(inicioDoBloco, totalPorBloco[i], falhasPorBloco[i]));
            total += totalPorBloco[i];
            falhas += falhasPorBloco[i];
        }

        return new Fita(inicioDaFita, fimDaFita, blocos, total, falhas);
    }
}

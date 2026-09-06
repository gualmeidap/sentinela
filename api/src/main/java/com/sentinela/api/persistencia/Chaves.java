package com.sentinela.api.persistencia;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Como um instante vira chave de ordenacao no DynamoDB.
 *
 * ARMADILHA QUE ISTO EVITA: a chave de ordenacao e comparada como TEXTO, letra
 * por letra. O Instant.toString() do Java omite os milissegundos quando eles
 * sao zero, e isso quebra a ordem:
 *
 *   "2026-09-01T10:00:00Z"       (sem milissegundos)
 *   "2026-09-01T10:00:00.500Z"   (com milissegundos)
 *
 * Cronologicamente o primeiro vem antes. Em texto, o caractere '.' vem antes de
 * 'Z' na tabela ASCII, entao o segundo seria considerado menor -- e a fita de
 * 24h devolveria os eventos fora de ordem, ou pior, deixaria de devolver os que
 * caem fora da faixa consultada.
 *
 * A correcao e um formato de largura fixa, sempre com tres casas de
 * milissegundo. Ai a ordem alfabetica e a ordem cronologica sao a mesma coisa.
 */
public final class Chaves {

    /** Largura fixa: "2026-09-01T10:00:00.000Z", sempre 24 caracteres. */
    public static final DateTimeFormatter FORMATO_DE_ORDENACAO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /**
     * Separador entre o instante e o identificador, nas chaves compostas.
     *
     * Como o instante tem largura fixa, dois instantes diferentes ja se
     * decidem nos 24 primeiros caracteres e o separador nunca chega a pesar na
     * ordem. Ele so importa no desempate, entre itens do mesmo instante.
     *
     * '#' foi escolhido por nao aparecer nem no formato ISO nem num UUID, o que
     * torna a chave sempre separavel de volta sem ambiguidade.
     */
    public static final String SEPARADOR = "#";

    private Chaves() {
    }

    public static String deInstante(Instant momento) {
        return FORMATO_DE_ORDENACAO.format(momento);
    }

    public static Instant paraInstante(String chave) {
        return Instant.from(FORMATO_DE_ORDENACAO.parse(chave));
    }

    /**
     * Chave composta para itens que podem coincidir no mesmo instante.
     *
     * O instante vem primeiro para a ordenacao continuar sendo cronologica; o
     * identificador entra so para desempatar e evitar que um item sobrescreva o
     * outro.
     */
    public static String deInstanteComId(Instant momento, String id) {
        return deInstante(momento) + SEPARADOR + id;
    }

    /**
     * Limite inferior de uma faixa de chave composta: o menor valor possivel
     * com aquele instante.
     */
    public static String inicioDaFaixa(Instant momento) {
        return deInstante(momento) + SEPARADOR;
    }

    /**
     * Limite superior EXCLUSIVO de uma faixa de chave composta.
     *
     * Devolve o instante sem separador. Toda chave real daquele instante e
     * "instante#id", ou seja, este limite mais alguma coisa -- e em comparacao
     * de texto uma string e sempre menor que outra que a tenha como comeco.
     * Logo todo item do instante final fica acima do limite e sai da faixa.
     *
     * Assim o "between" do DynamoDB, que e inclusivo nas duas pontas, se
     * comporta como [inicio, fim).
     */
    public static String fimDaFaixaExclusivo(Instant momento) {
        return deInstante(momento);
    }

    /** Momento em que o item deve sumir sozinho, em segundos desde 1970 (formato do TTL). */
    public static long expiraEm(Instant momento, Duration retencao) {
        return momento.plus(retencao).getEpochSecond();
    }
}

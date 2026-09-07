package com.sentinela.coletor;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * O formato com que este projeto escreve no DynamoDB.
 *
 * ATENCAO: este e o CONTRATO entre o coletor e a API. As duas pecas nao
 * compartilham codigo de proposito -- um jar comum viraria um caminho de
 * acoplamento por onde a mudanca de uma quebraria a outra em producao. O preco
 * dessa escolha e este arquivo: ele espelha o Chaves do api/, e os dois
 * precisam concordar.
 *
 * O que mantem os dois honestos e o formato literal estar fixado em teste dos
 * DOIS lados: "2026-09-01T10:00:00.000Z". Mudar um sem o outro deixa a fita do
 * painel vazia sem nenhum erro aparecer, entao o literal e o alarme.
 *
 * Por que largura fixa: a chave de ordenacao do DynamoDB e comparada como
 * texto, e o Instant.toString() do Java omite os milissegundos quando sao zero.
 * Isso poe "10:00:00.500Z" antes de "10:00:00Z", porque o ponto tem codigo
 * menor que o Z -- e a ordem cronologica deixa de valer.
 */
public final class ChavesDoBanco {

    /** Nomes dos campos na tabela. Precisam bater com os que a API le. */
    public static final String PARTICAO = "sistemaId";
    public static final String ORDENACAO = "momento";
    public static final String RESPONDEU = "respondeu";
    public static final String TEMPO_RESPOSTA = "tempoRespostaMs";
    public static final String EXPIRA_EM = "expiraEm";

    /**
     * Campos que so o coletor grava. A API os ignora hoje, e continua
     * funcionando: o DynamoDB nao tem esquema fixo, entao as duas pecas podem
     * evoluir em ritmos diferentes sem uma quebrar a outra.
     */
    public static final String MOTIVO = "motivo";
    public static final String STATUS_HTTP = "statusHttp";

    private static final DateTimeFormatter FORMATO =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private ChavesDoBanco() {
    }

    public static String deInstante(Instant momento) {
        return FORMATO.format(momento);
    }

    /** Momento em que o item deve sumir sozinho, no formato do TTL do DynamoDB. */
    public static long expiraEm(Instant momento, Duration retencao) {
        return momento.plus(retencao).getEpochSecond();
    }
}

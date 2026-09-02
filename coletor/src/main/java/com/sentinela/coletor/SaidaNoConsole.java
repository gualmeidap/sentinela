package com.sentinela.coletor;

import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Imprime a rodada em formato legivel por gente.
 *
 * E a saida do passo 5 rodando local: serve para conferir que o coletor
 * funciona antes de existir banco para ele escrever.
 */
public class SaidaNoConsole implements RegistroDeVerificacoes {

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final PrintStream saida;
    private final ZoneId zona;

    public SaidaNoConsole(PrintStream saida, ZoneId zona) {
        this.saida = saida;
        this.zona = zona;
    }

    @Override
    public void registrar(List<Verificacao> verificacoes) {
        for (Verificacao verificacao : verificacoes) {
            saida.printf("  %-8s  %-22s  %s%n",
                    HORA.format(verificacao.momento().atZone(zona)),
                    verificacao.sistemaId(),
                    descrever(verificacao));
        }

        long fora = verificacoes.stream().filter(v -> !v.respondeu()).count();
        saida.printf("  %d alvo(s) verificado(s), %d fora do ar%n", verificacoes.size(), fora);
    }

    private String descrever(Verificacao verificacao) {
        if (verificacao.respondeu()) {
            return String.format("no ar     %5d ms  HTTP %d",
                    verificacao.tempoRespostaMs(), verificacao.statusHttp());
        }
        String status = verificacao.statusHttp() == null ? "" : "  HTTP " + verificacao.statusHttp();
        return String.format("FORA      %5d ms  %s%s",
                verificacao.tempoRespostaMs(), verificacao.motivo(), status);
    }
}

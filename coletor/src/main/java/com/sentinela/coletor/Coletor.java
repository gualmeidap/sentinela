package com.sentinela.coletor;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Uma rodada de verificacao: pergunta a todos os alvos e entrega os resultados.
 *
 * Os alvos sao verificados em paralelo, com threads virtuais do Java 21. Nao e
 * enfeite: a espera aqui e de rede, nao de processamento, e verificar dez
 * alvos em serie custaria a soma de dez esperas. Como a Lambda cobra por tempo
 * de execucao, serializar seria pagar dez vezes por algo que acontece de uma
 * vez so.
 *
 * Thread virtual, diferente da tradicional, nao ocupa uma thread do sistema
 * enquanto espera resposta -- entao criar uma por alvo sai barato, mesmo que
 * um dia sejam centenas.
 */
public class Coletor {

    private final VerificadorHttp verificador;
    private final RegistroDeVerificacoes registro;
    private final Clock relogio;

    public Coletor(VerificadorHttp verificador, RegistroDeVerificacoes registro, Clock relogio) {
        this.verificador = verificador;
        this.registro = registro;
        this.relogio = relogio;
    }

    public List<Verificacao> executar(List<Alvo> alvos) {
        if (alvos.isEmpty()) {
            return List.of();
        }

        // try-with-resources num ExecutorService: o close() espera as tarefas
        // terminarem. Sem isso, a Lambda poderia encerrar com verificacao pela
        // metade.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Verificacao>> futuros = alvos.stream()
                    .map(alvo -> executor.submit(() -> verificador.verificar(alvo)))
                    .toList();

            List<Verificacao> resultados = new ArrayList<>(alvos.size());
            for (int i = 0; i < alvos.size(); i++) {
                resultados.add(colher(alvos.get(i), futuros.get(i)));
            }

            registro.registrar(resultados);
            return List.copyOf(resultados);
        }
    }

    /**
     * O verificador foi escrito para nunca lancar excecao, entao nada aqui
     * deveria falhar. "Deveria" nao e garantia: se falhar, o alvo vira uma
     * verificacao de erro em vez de derrubar a rodada inteira e apagar os
     * outros nove resultados.
     */
    private Verificacao colher(Alvo alvo, Future<Verificacao> futuro) {
        try {
            return futuro.get();
        } catch (ExecutionException falhaInesperada) {
            return Verificacao.falhou(alvo.id(), relogio.instant(), 0, MotivoDaFalha.ERRO_INESPERADO, null);
        } catch (InterruptedException interrompido) {
            Thread.currentThread().interrupt();
            return Verificacao.falhou(alvo.id(), relogio.instant(), 0, MotivoDaFalha.ERRO_INESPERADO, null);
        }
    }

    public static void main(String[] argumentos) {
        Clock relogio = Clock.systemUTC();
        try {
            Configuracao configuracao = Configuracao.de(argumentos, System.getenv());
            Coletor coletor = new Coletor(
                    new VerificadorHttp(configuracao.tempoLimite(), relogio),
                    new SaidaNoConsole(System.out, ZoneId.systemDefault()),
                    relogio);

            System.out.printf("verificando %d alvo(s), tempo limite de %d ms%n",
                    configuracao.alvos().size(), configuracao.tempoLimite().toMillis());
            coletor.executar(configuracao.alvos());

        } catch (IllegalArgumentException configuracaoInvalida) {
            // Sai com codigo diferente de zero para que o EventBridge, mais
            // adiante, consiga distinguir "rodou e achou tudo fora do ar" de
            // "nem chegou a rodar".
            System.err.println("configuracao invalida: " + configuracaoInvalida.getMessage());
            System.exit(2);
        }
    }
}

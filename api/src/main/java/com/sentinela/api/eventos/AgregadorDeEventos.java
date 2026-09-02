package com.sentinela.api.eventos;

import com.sentinela.api.eventos.ResumoDoDia.ResumoPorTipo;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A regra de agregacao dos eventos.
 *
 * Como a CalculadoraDeDisponibilidade: sem Spring, sem banco, sem HTTP. Entra
 * lista de evento, sai resumo -- e por isso da para testar cada caso em
 * milissegundos.
 */
public final class AgregadorDeEventos {

    private AgregadorDeEventos() {
    }

    public static ResumoDoDia resumir(List<Evento> eventos, LocalDate dia) {
        // TreeMap para os tipos sairem em ordem alfabetica: a tela nao pode
        // trocar a ordem dos blocos a cada atualizacao so porque a contagem
        // mudou.
        Map<String, Contador> porTipo = new TreeMap<>();
        for (Evento evento : eventos) {
            porTipo.computeIfAbsent(evento.tipo(), tipo -> new Contador()).somar(evento);
        }

        List<ResumoPorTipo> tipos = new ArrayList<>(porTipo.size());
        int sucessos = 0;
        int falhas = 0;
        for (Map.Entry<String, Contador> entrada : porTipo.entrySet()) {
            Contador contador = entrada.getValue();
            tipos.add(new ResumoPorTipo(entrada.getKey(), contador.sucessos, contador.falhas,
                    contador.motivosOrdenados()));
            sucessos += contador.sucessos;
            falhas += contador.falhas;
        }

        return new ResumoDoDia(dia, sucessos + falhas, sucessos, falhas, tipos);
    }

    private static final class Contador {
        private int sucessos;
        private int falhas;
        private final Map<String, Integer> motivos = new TreeMap<>();

        private void somar(Evento evento) {
            if (evento.falhou()) {
                falhas++;
                motivos.merge(evento.motivo(), 1, Integer::sum);
            } else {
                sucessos++;
            }
        }

        /**
         * Do motivo mais frequente para o menos. Empate desempata pelo nome, e
         * nao pela ordem em que os eventos chegaram -- senao duas chamadas
         * seguidas devolveriam listas diferentes para os mesmos dados.
         */
        private Map<String, Integer> motivosOrdenados() {
            Map<String, Integer> ordenado = new LinkedHashMap<>();
            motivos.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .forEach(entrada -> ordenado.put(entrada.getKey(), entrada.getValue()));
            return ordenado;
        }
    }
}

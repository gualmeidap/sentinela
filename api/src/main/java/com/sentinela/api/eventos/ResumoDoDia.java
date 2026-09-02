package com.sentinela.api.eventos;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A contagem do dia de um sistema, agrupada por tipo e resultado.
 *
 * E o "47 sucesso, 3 falha" da tela, com a quebra por motivo junto: saber que
 * houve 3 falhas nao ajuda ninguem; saber que as 3 foram pela mesma dependencia
 * fora do ar aponta direto para onde olhar.
 */
public record ResumoDoDia(
        LocalDate dia,
        int total,
        int sucessos,
        int falhas,
        List<ResumoPorTipo> tipos) {

    public ResumoDoDia {
        tipos = List.copyOf(tipos);
    }

    /**
     * Um tipo de evento e como ele se saiu no dia.
     *
     * falhasPorMotivo vem ordenado do motivo mais frequente para o menos: numa
     * lista de motivos, o que aparece mais e quase sempre o que interessa
     * primeiro.
     */
    public record ResumoPorTipo(String tipo, int sucessos, int falhas, Map<String, Integer> falhasPorMotivo) {

        public ResumoPorTipo {
            // LinkedHashMap, e nao Map.copyOf: este ultimo devolve mapa sem
            // ordem definida, e a ordem aqui e informacao -- o motivo mais
            // frequente precisa continuar aparecendo primeiro na tela.
            falhasPorMotivo = Collections.unmodifiableMap(new LinkedHashMap<>(falhasPorMotivo));
        }

        public int total() {
            return sucessos + falhas;
        }
    }
}

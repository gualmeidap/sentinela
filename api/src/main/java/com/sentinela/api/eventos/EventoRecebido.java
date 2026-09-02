package com.sentinela.api.eventos;

import java.time.Instant;
import java.util.Map;

/**
 * O corpo do POST /eventos exatamente como o JSON chega, sem regra nenhuma.
 *
 * Existe separado do Evento por um motivo pratico: se a validacao morasse no
 * objeto que o Jackson constroi, ela rodaria dentro da desserializacao, e uma
 * excecao ali vira 500 com mensagem que pode arrastar pedaco do payload junto.
 * Validando depois, em codigo nosso, a recusa sai como 400 com a mensagem que
 * nos escolhemos -- o que importa quando o payload pode conter o que nao
 * deveria.
 */
public record EventoRecebido(
        String sistema,
        String tipo,
        String resultado,
        String motivo,
        Instant ocorridoEm,
        Map<String, String> contexto) {
}

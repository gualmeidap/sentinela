package com.sentinela.api.disponibilidade;

import com.sentinela.api.sistema.SistemaMonitorado;
import java.time.Instant;

/**
 * O estado de um sistema agora: o indicador verde ou vermelho da tela, o tempo
 * de resposta da ultima checagem e quando ela aconteceu.
 */
public record EstadoAtual(SistemaMonitorado sistema, Situacao situacao, Integer tempoRespostaMs,
                          Instant ultimaVerificacao) {

    public enum Situacao {
        /** A ultima verificacao respondeu. */
        NO_AR,
        /** A ultima verificacao nao respondeu. */
        FORA_DO_AR,
        /** Nunca houve verificacao deste sistema. */
        SEM_DADO
    }

    public static EstadoAtual de(SistemaMonitorado sistema, Verificacao ultima) {
        Situacao situacao = ultima.respondeu() ? Situacao.NO_AR : Situacao.FORA_DO_AR;
        return new EstadoAtual(sistema, situacao, ultima.tempoRespostaMs(), ultima.momento());
    }

    public static EstadoAtual semDado(SistemaMonitorado sistema) {
        return new EstadoAtual(sistema, Situacao.SEM_DADO, null, null);
    }
}

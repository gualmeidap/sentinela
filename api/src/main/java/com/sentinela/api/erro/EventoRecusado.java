package com.sentinela.api.erro;

/**
 * Evento fora do contrato. Vira 400.
 *
 * O codigo e um identificador fechado ("tipo_desconhecido", "motivo_em_sucesso")
 * para o publicador tratar sem depender do texto. O que ele nunca contem e o
 * valor recusado: devolver de volta o que veio faria a resposta e o log
 * carregarem exatamente aquilo que a lista fechada existe para barrar.
 */
public class EventoRecusado extends RuntimeException {

    private final transient String codigo;

    public EventoRecusado(String codigo, String explicacao) {
        super(explicacao);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}

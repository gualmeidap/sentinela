package com.sentinela.coletor;

import java.util.List;

/**
 * Entrega a mesma rodada a mais de um destino.
 *
 * Na Lambda isso vale a pena: o que vai para a saida padrao cai no CloudWatch
 * Logs, entao imprimir continua util mesmo com o banco configurado -- da para
 * ver o que a funcao mediu sem consultar tabela nenhuma.
 *
 * A ordem importa. O console vem primeiro justamente para que, se a gravacao
 * falhar, o resultado da rodada ainda esteja registrado em algum lugar antes de
 * a excecao subir.
 */
public class RegistroCombinado implements RegistroDeVerificacoes {

    private final List<RegistroDeVerificacoes> destinos;

    public RegistroCombinado(RegistroDeVerificacoes... destinos) {
        this.destinos = List.of(destinos);
    }

    @Override
    public void registrar(List<Verificacao> verificacoes) {
        for (RegistroDeVerificacoes destino : destinos) {
            destino.registrar(verificacoes);
        }
    }
}

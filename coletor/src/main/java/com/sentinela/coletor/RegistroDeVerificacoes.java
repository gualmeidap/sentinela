package com.sentinela.coletor;

import java.util.List;

/**
 * Para onde vao os resultados da rodada.
 *
 * Hoje so existe a saida no console. No passo 4 entra a implementacao que
 * grava no DynamoDB, e e nesse momento que o coletor e a API passam a se
 * encontrar -- no banco, que e o unico ponto de encontro previsto.
 *
 * Nao ha implementacao "grava em arquivo" de proposito: seria um banco
 * improvisado, com formato para manter e migrar, jogado fora duas semanas
 * depois.
 */
public interface RegistroDeVerificacoes {

    void registrar(List<Verificacao> verificacoes);
}

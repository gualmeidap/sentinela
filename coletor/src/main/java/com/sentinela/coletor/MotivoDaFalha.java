package com.sentinela.coletor;

/**
 * Por que um alvo nao respondeu.
 *
 * Lista fechada, pelo mesmo motivo dos eventos de negocio: guardar a mensagem
 * crua da excecao carregaria caminho interno e endereco junto, e tornaria
 * impossivel agrupar. "12 falhas, todas por tempo esgotado" e a informacao
 * util; doze mensagens de pilha diferentes nao sao.
 *
 * O briefing pede comportamento definido para cada modo de falha -- e definir
 * comportamento comeca por conseguir distinguir um do outro.
 */
public enum MotivoDaFalha {

    /** O alvo nao respondeu dentro do tempo limite. Esta de pe, mas lento demais. */
    TEMPO_ESGOTADO,

    /** Nao foi possivel abrir conexao: porta fechada, host inexistente, DNS falhando. */
    CONEXAO_RECUSADA,

    /** O alvo respondeu, mas com status de erro (>= 400). Servidor de pe, aplicacao quebrada. */
    STATUS_DE_ERRO,

    /** Qualquer outra coisa. Existe para que nada escape sem classificacao. */
    ERRO_INESPERADO
}

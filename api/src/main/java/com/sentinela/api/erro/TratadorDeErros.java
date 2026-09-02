package com.sentinela.api.erro;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Transforma excecao de dominio em resposta HTTP com corpo previsivel.
 *
 * @RestControllerAdvice e um interceptador global: vale para todos os
 * controllers, sem que nenhum deles precise de try/catch. E o equivalente do
 * exception_handler do FastAPI.
 *
 * O corpo segue o formato ProblemDetail (RFC 7807), que e o padrao do Spring
 * para erro em API. O campo "codigo" e adicional e existe para o cliente
 * decidir o que fazer sem depender do texto, que e para gente ler.
 *
 * Nenhum handler aqui devolve conteudo vindo da requisicao. Num portal cuja
 * regra e que dado pessoal nao atravessa, a mensagem de erro e justamente por
 * onde ele atravessaria sem ninguem perceber.
 */
@RestControllerAdvice
public class TratadorDeErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    @ExceptionHandler(SistemaNaoEncontrado.class)
    public ProblemDetail sistemaNaoEncontrado(SistemaNaoEncontrado excecao) {
        ProblemDetail problema = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problema.setTitle("Sistema nao encontrado");
        problema.setDetail("Nao existe sistema monitorado com o id informado.");
        problema.setProperty("codigo", "sistema_nao_encontrado");
        problema.setProperty("sistemaId", excecao.sistemaId());
        return problema;
    }

    @ExceptionHandler(ChaveInvalida.class)
    public ProblemDetail chaveInvalida(ChaveInvalida excecao) {
        ProblemDetail problema = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problema.setTitle("Chave invalida");
        problema.setDetail("Informe uma chave de aplicacao valida no cabecalho X-Chave-Aplicacao.");
        problema.setProperty("codigo", "chave_invalida");
        return problema;
    }

    @ExceptionHandler(PublicacaoNaoAutorizada.class)
    public ProblemDetail publicacaoNaoAutorizada(PublicacaoNaoAutorizada excecao) {
        ProblemDetail problema = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problema.setTitle("Publicacao nao autorizada");
        problema.setDetail("A chave apresentada publica apenas para o proprio sistema.");
        problema.setProperty("codigo", "publicacao_nao_autorizada");
        return problema;
    }

    @ExceptionHandler(EventoRecusado.class)
    public ProblemDetail eventoRecusado(EventoRecusado excecao) {
        ProblemDetail problema = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problema.setTitle("Evento recusado");
        problema.setDetail(excecao.getMessage());
        problema.setProperty("codigo", excecao.codigo());
        return problema;
    }

    @ExceptionHandler(LimiteExcedido.class)
    public ProblemDetail limiteExcedido(LimiteExcedido excecao) {
        ProblemDetail problema = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problema.setTitle("Limite de escrita excedido");
        problema.setDetail("A aplicacao passou do volume combinado e esta silenciada ate o fim da hora.");
        problema.setProperty("codigo", "limite_excedido");
        problema.setProperty("limitePorHora", excecao.limitePorHora());
        return problema;
    }

    /**
     * JSON que o Jackson nao conseguiu ler: campo com tipo errado, data
     * malformada, corpo truncado.
     *
     * A mensagem original do Jackson costuma citar o trecho do payload que
     * causou o problema -- e o payload e exatamente o que nao pode ser ecoado
     * nem registrado. Por isso a resposta e fixa e o log guarda so a classe da
     * excecao.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail corpoIlegivel(HttpMessageNotReadableException excecao) {
        log.warn("corpo de requisicao ilegivel: {}", excecao.getClass().getSimpleName());
        ProblemDetail problema = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problema.setTitle("Corpo invalido");
        problema.setDetail("O corpo da requisicao nao e um JSON valido para este endpoint.");
        problema.setProperty("codigo", "corpo_ilegivel");
        return problema;
    }
}

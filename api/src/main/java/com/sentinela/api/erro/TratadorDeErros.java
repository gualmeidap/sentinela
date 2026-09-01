package com.sentinela.api.erro;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
 */
@RestControllerAdvice
public class TratadorDeErros {

    @ExceptionHandler(SistemaNaoEncontrado.class)
    public ProblemDetail sistemaNaoEncontrado(SistemaNaoEncontrado excecao) {
        ProblemDetail problema = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problema.setTitle("Sistema nao encontrado");
        problema.setDetail("Nao existe sistema monitorado com o id informado.");
        problema.setProperty("codigo", "sistema_nao_encontrado");
        problema.setProperty("sistemaId", excecao.sistemaId());
        return problema;
    }
}

package com.sentinela.pagina;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Serve o painel: index.html, estilo.css e painel.js, empacotados dentro do
 * proprio jar como recursos.
 *
 * Nao ha servidor de arquivos, nao ha S3, nao ha framework web -- so um mapa
 * de caminho para recurso e uma leitura de bytes. E deliberadamente o handler
 * mais simples deste repositorio: quanto menos codigo entre a requisicao e a
 * resposta, mais rapido o cold start, e essa funcao e a primeira coisa que
 * carrega quando alguem abre o link.
 *
 * Os arquivos nao vem de web/ diretamente -- eles sao copiados para
 * src/main/resources antes do build por infra/preparar_pagina.py, que tambem
 * troca o endereco da API que vem escrito em painel.js. web/ continua sendo a
 * unica fonte de verdade; o que fica aqui e uma copia gerada, nunca editada a
 * mao (por isso esta fora do controle de versao).
 */
public class ManipuladorDaPagina implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private record Arquivo(String recurso, String tipoDeConteudo) {
    }

    private static final Map<String, Arquivo> ARQUIVOS = Map.of(
            "/", new Arquivo("/index.html", "text/html; charset=utf-8"),
            "/index.html", new Arquivo("/index.html", "text/html; charset=utf-8"),
            "/estilo.css", new Arquivo("/estilo.css", "text/css; charset=utf-8"),
            "/painel.js", new Arquivo("/painel.js", "text/javascript; charset=utf-8"));

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> evento, Context contexto) {
        Arquivo arquivo = ARQUIVOS.get(caminhoDaRequisicao(evento));
        if (arquivo == null) {
            return resposta(404, "text/plain; charset=utf-8", "nao encontrado");
        }

        try {
            return resposta(200, arquivo.tipoDeConteudo(), lerRecurso(arquivo.recurso()));
        } catch (UncheckedIOException falha) {
            // So aconteceria se o jar fosse empacotado sem os recursos -- um
            // erro de build, nunca de requisicao. Nao ha caminho para o
            // cliente corrigir isso reenviando nada.
            return resposta(500, "text/plain; charset=utf-8", "erro ao servir a pagina");
        }
    }

    /**
     * "rawPath" e o campo que a Lambda preenche com o caminho da requisicao,
     * no formato de payload 2.0 que toda Function URL usa. Sem caminho
     * nenhum -- o que a AWS documenta como possivel, embora raro -- trata como
     * a raiz.
     */
    private String caminhoDaRequisicao(Map<String, Object> evento) {
        Object rawPath = evento.get("rawPath");
        return rawPath == null ? "/" : rawPath.toString();
    }

    private String lerRecurso(String nome) {
        try (InputStream entrada = getClass().getResourceAsStream(nome)) {
            if (entrada == null) {
                throw new IOException("recurso nao empacotado no jar: " + nome);
            }
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            entrada.transferTo(saida);
            return saida.toString(StandardCharsets.UTF_8);
        } catch (IOException falha) {
            throw new UncheckedIOException(falha);
        }
    }

    private Map<String, Object> resposta(int status, String tipoDeConteudo, String corpo) {
        return Map.of(
                "statusCode", status,
                "headers", Map.of("content-type", tipoDeConteudo),
                "body", corpo,
                "isBase64Encoded", false);
    }
}

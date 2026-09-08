package com.sentinela.pagina;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Estes testes exigem que infra/preparar_pagina.py ja tenha rodado, copiando
 * web/ para src/main/resources -- e o que o pom.xml/CI fazem antes de chamar
 * o Maven. Sem isso, os recursos nao existem no classpath e os testes falham
 * de um jeito que aponta direto para a causa.
 */
class ManipuladorDaPaginaTest {

    private final ManipuladorDaPagina manipulador = new ManipuladorDaPagina();

    private Map<String, Object> pedir(String caminho) {
        return manipulador.handleRequest(Map.of("rawPath", caminho), null);
    }

    @SuppressWarnings("unchecked")
    private String tipoDeConteudo(Map<String, Object> resposta) {
        return (String) ((Map<String, Object>) resposta.get("headers")).get("content-type");
    }

    @Test
    @DisplayName("a raiz devolve o index.html")
    void raizDevolveIndex() {
        Map<String, Object> resposta = pedir("/");

        assertThat(resposta.get("statusCode")).isEqualTo(200);
        assertThat(tipoDeConteudo(resposta)).isEqualTo("text/html; charset=utf-8");
        assertThat((String) resposta.get("body")).contains("<title>Sentinela</title>");
        assertThat(resposta.get("isBase64Encoded")).isEqualTo(false);
    }

    @Test
    @DisplayName("/index.html devolve o mesmo conteudo que a raiz")
    void indexHtmlExplicito() {
        assertThat(pedir("/index.html").get("body")).isEqualTo(pedir("/").get("body"));
    }

    @Test
    @DisplayName("/estilo.css devolve o CSS com o tipo certo")
    void estiloCss() {
        Map<String, Object> resposta = pedir("/estilo.css");

        assertThat(resposta.get("statusCode")).isEqualTo(200);
        assertThat(tipoDeConteudo(resposta)).isEqualTo("text/css; charset=utf-8");
        assertThat((String) resposta.get("body")).contains("--fundo");
    }

    @Test
    @DisplayName("/painel.js devolve o JS com o tipo certo")
    void painelJs() {
        Map<String, Object> resposta = pedir("/painel.js");

        assertThat(resposta.get("statusCode")).isEqualTo(200);
        assertThat(tipoDeConteudo(resposta)).isEqualTo("text/javascript; charset=utf-8");
        assertThat((String) resposta.get("body")).contains("const API");
    }

    @Test
    @DisplayName("caminho desconhecido devolve 404, sem derrubar a funcao")
    void caminhoDesconhecidoDevolve404() {
        Map<String, Object> resposta = pedir("/nao-existe");

        assertThat(resposta.get("statusCode")).isEqualTo(404);
    }

    @Test
    @DisplayName("evento sem rawPath e tratado como a raiz")
    void semRawPathTrataComoRaiz() {
        Map<String, Object> resposta = manipulador.handleRequest(Map.of(), null);

        assertThat(resposta.get("statusCode")).isEqualTo(200);
    }
}

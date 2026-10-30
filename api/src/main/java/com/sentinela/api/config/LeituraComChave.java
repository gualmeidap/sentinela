package com.sentinela.api.config;

import com.sentinela.api.erro.LeituraNaoAutorizada;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Barra a requisicao que nao traz a chave de leitura.
 *
 * Um interceptador roda antes do controller, para toda requisicao do caminho em
 * que foi registrado -- e o lugar de uma regra que vale para varios endpoints
 * sem que nenhum deles precise lembrar de aplica-la. A excecao lancada aqui
 * cai no TratadorDeErros, como se tivesse saido de um controller.
 *
 * A comparacao e feita entre resumos SHA-256, em tempo constante. Comparar o
 * texto com equals() para no primeiro caractere diferente, e o tempo da
 * resposta ensina a quem tenta quantos caracteres ja acertou.
 */
public class LeituraComChave implements HandlerInterceptor {

    public static final String CABECALHO = "X-Chave-Leitura";

    private final byte[] resumoDaChave;

    public LeituraComChave(String chave) {
        this.resumoDaChave = resumo(chave);
    }

    @Override
    public boolean preHandle(HttpServletRequest requisicao, HttpServletResponse resposta, Object manipulador) {
        // O pre-voo de CORS e o navegador perguntando "posso?" antes de enviar o
        // cabecalho de verdade; ele nunca leva a chave e nao devolve dado.
        if (CorsUtils.isPreFlightRequest(requisicao)) {
            return true;
        }
        String recebida = requisicao.getHeader(CABECALHO);
        if (recebida == null || !MessageDigest.isEqual(resumo(recebida), resumoDaChave)) {
            throw new LeituraNaoAutorizada();
        }
        return true;
    }

    private static byte[] resumo(String texto) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(texto.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossivel) {
            // SHA-256 e obrigatorio em toda JVM; chegar aqui e ambiente quebrado.
            throw new IllegalStateException(impossivel);
        }
    }
}

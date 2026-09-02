package com.sentinela.api.eventos;

import com.sentinela.api.config.AplicacaoPublicadora;
import com.sentinela.api.config.PropriedadesDoSentinela;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Descobre qual aplicacao esta por tras de uma chave apresentada.
 *
 * Nenhuma aplicacao configurada significa que ninguem publica: o portal recusa
 * tudo. E o padrao seguro -- esquecer de configurar fecha a porta em vez de
 * abri-la.
 */
@Component
public class CatalogoDeAplicacoes {

    private final List<AplicacaoPublicadora> aplicacoes;

    public CatalogoDeAplicacoes(PropriedadesDoSentinela propriedades) {
        this.aplicacoes = propriedades.aplicacoes();
    }

    public Optional<AplicacaoPublicadora> porChave(String chaveApresentada) {
        if (chaveApresentada == null || chaveApresentada.isBlank()) {
            return Optional.empty();
        }

        // O laco percorre a lista inteira mesmo depois de achar. Sair no
        // primeiro acerto faria o tempo de resposta variar conforme a posicao da
        // chave na lista, e tempo de resposta e informacao que vaza.
        AplicacaoPublicadora encontrada = null;
        for (AplicacaoPublicadora aplicacao : aplicacoes) {
            if (chavesIguais(aplicacao.chave(), chaveApresentada)) {
                encontrada = aplicacao;
            }
        }
        return Optional.ofNullable(encontrada);
    }

    public Optional<AplicacaoPublicadora> porId(String id) {
        return aplicacoes.stream().filter(aplicacao -> aplicacao.id().equals(id)).findFirst();
    }

    public List<AplicacaoPublicadora> todas() {
        return aplicacoes;
    }

    /**
     * Comparacao em tempo constante.
     *
     * O "equals" de String para na primeira letra diferente, e essa diferenca de
     * microssegundos e medivel: com paciencia, da para descobrir uma chave letra
     * por letra so cronometrando as respostas. MessageDigest.isEqual compara
     * todos os bytes sempre.
     */
    private boolean chavesIguais(String esperada, String apresentada) {
        return MessageDigest.isEqual(
                esperada.getBytes(StandardCharsets.UTF_8),
                apresentada.getBytes(StandardCharsets.UTF_8));
    }
}

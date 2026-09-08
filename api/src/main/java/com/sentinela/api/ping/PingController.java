package com.sentinela.api.ping;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de teste, e a raiz da API.
 *
 * @RestController = @Controller + @ResponseBody: o retorno do metodo vira o
 * corpo da resposta, serializado em JSON pelo Jackson. Nao existe template,
 * nao existe view.
 */
@RestController
public class PingController {

    @GetMapping("/ping")
    public Map<String, String> ping() {
        return Map.of(
                "status", "ok",
                "servico", "sentinela-api",
                "instante", Instant.now().toString());
    }

    /**
     * A API nao tem pagina nenhuma na raiz -- quem desenha a tela e o painel,
     * em web/, servido separadamente. Sem isto, alguem que abrisse so o
     * endereco base da API (sem saber que precisa completar com /sistemas)
     * cairia na pagina de erro generica do Spring, o que aconteceu de verdade
     * na primeira vez que a API publica foi visitada. Esta resposta troca
     * aquilo por um ponto de partida que se explica sozinho.
     */
    @GetMapping("/")
    public Map<String, Object> raiz() {
        return Map.of(
                "servico", "sentinela-api",
                "endpoints", Map.of(
                        "disponibilidade", "/sistemas",
                        "eventos", "/sistemas/{id}/eventos",
                        "publicarEvento", "POST /eventos"));
    }
}

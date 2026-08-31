package com.sentinela.api.ping;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint de teste. Existe so para confirmar que a aplicacao sobe e responde.
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
}

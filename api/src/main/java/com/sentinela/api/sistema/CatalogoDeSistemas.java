package com.sentinela.api.sistema;

import com.sentinela.api.config.PropriedadesDoSentinela;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A lista dos sistemas monitorados, vinda de configuracao externa.
 *
 * Ate o passo 6 esta lista era um List.of() fixo aqui dentro, o combinado do
 * passo 1. Ela saiu do codigo quando a instancia privada precisou declarar os
 * sistemas reais: nome de sistema real nao pode aparecer em arquivo
 * versionado, e o application-{perfil}.yml da instancia privada nao e.
 *
 * Estar nesta lista nao significa "tem disponibilidade verificada". Significa
 * "a API conhece este id" -- e isso e pre-requisito tanto para
 * GET /sistemas/{id}/disponibilidade quanto para GET /sistemas/{id}/eventos.
 * Um sistema que so publica evento, e nunca e verificado, entra aqui sem url.
 *
 * @Component registra a classe no contexto do Spring, para que ela possa ser
 * injetada em quem precisar. Equivale a declarar uma dependencia no Depends do
 * FastAPI, so que a ligacao e feita por tipo, uma vez, na subida.
 */
@Component
public class CatalogoDeSistemas {

    private final List<SistemaMonitorado> sistemas;

    public CatalogoDeSistemas(PropriedadesDoSentinela propriedades) {
        this.sistemas = propriedades.sistemas();
    }

    public List<SistemaMonitorado> todos() {
        return sistemas;
    }

    public Optional<SistemaMonitorado> porId(String id) {
        return sistemas.stream()
                .filter(sistema -> sistema.id().equals(id))
                .findFirst();
    }
}

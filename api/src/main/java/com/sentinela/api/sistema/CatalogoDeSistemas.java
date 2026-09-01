package com.sentinela.api.sistema;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A lista dos sistemas monitorados.
 *
 * PASSO 1 da ordem de execucao: a lista fica fixa no codigo, de proposito e por
 * pouco tempo. Na versao final ela vem de configuracao externa, nunca do
 * repositorio -- e exatamente por isso que nenhum alvo real aparece aqui.
 *
 * Os alvos abaixo sao ficticios: "example.com" e reservado para documentacao
 * pela RFC 2606 e nunca vai pertencer a ninguem.
 *
 * @Component registra a classe no contexto do Spring, para que ela possa ser
 * injetada em quem precisar. Equivale a declarar uma dependencia no Depends do
 * FastAPI, so que a ligacao e feita por tipo, uma vez, na subida.
 */
@Component
public class CatalogoDeSistemas {

    private static final List<SistemaMonitorado> SISTEMAS = List.of(
            new SistemaMonitorado("portal-servicos", "Portal de Servicos",
                    URI.create("https://portal.example.com")),
            new SistemaMonitorado("api-integracao", "API de Integracao",
                    URI.create("https://api.example.com")),
            new SistemaMonitorado("agendamento", "Agendamento Online",
                    URI.create("https://agendamento.example.com")));

    public List<SistemaMonitorado> todos() {
        return SISTEMAS;
    }

    public Optional<SistemaMonitorado> porId(String id) {
        return SISTEMAS.stream()
                .filter(sistema -> sistema.id().equals(id))
                .findFirst();
    }
}

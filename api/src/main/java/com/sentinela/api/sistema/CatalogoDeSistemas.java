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
 * Os alvos abaixo sao servicos publicos de verdade -- a instancia publica pode
 * monitorar "alvos ficticios ou servicos publicos", e servico real da um
 * diferencial: o painel mostra disponibilidade e tempo de resposta medidos de
 * verdade, em vez de dado que nunca e checado.
 *
 * A escolha nao foi a primeira tentativa. Subdominios de example.com
 * ("portal.example.com" etc.) pareciam uma opcao segura por serem ficticios,
 * mas so o dominio raiz e reservado pela RFC 2606 -- os subdominios nao tem
 * servidor nenhum por tras. Isso so apareceu na hora de ligar o coletor de
 * verdade contra a AWS: localmente o coletor sempre foi testado contra
 * localhost, nunca contra esses enderecos.
 *
 * @Component registra a classe no contexto do Spring, para que ela possa ser
 * injetada em quem precisar. Equivale a declarar uma dependencia no Depends do
 * FastAPI, so que a ligacao e feita por tipo, uma vez, na subida.
 */
@Component
public class CatalogoDeSistemas {

    private static final List<SistemaMonitorado> SISTEMAS = List.of(
            new SistemaMonitorado("portal-servicos", "Portal de Servicos",
                    URI.create("https://github.com")),
            new SistemaMonitorado("api-integracao", "API de Integracao",
                    URI.create("https://api.github.com")),
            new SistemaMonitorado("agendamento", "Agendamento Online",
                    URI.create("https://www.wikipedia.org")));

    public List<SistemaMonitorado> todos() {
        return SISTEMAS;
    }

    public Optional<SistemaMonitorado> porId(String id) {
        return SISTEMAS.stream()
                .filter(sistema -> sistema.id().equals(id))
                .findFirst();
    }
}

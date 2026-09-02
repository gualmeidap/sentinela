package com.sentinela.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuracao externa da aplicacao, tudo que vive sob o prefixo "sentinela" no
 * application.yml.
 *
 * @ConfigurationProperties liga um trecho do arquivo de configuracao a um
 * objeto Java, validando o tipo na subida: se alguem escrever texto onde se
 * espera numero, a aplicacao nem sobe -- em vez de quebrar as tres da manha na
 * primeira requisicao que usar o campo. E o equivalente de um BaseSettings do
 * pydantic.
 *
 * A ligacao entre nome de campo e nome de propriedade e frouxa de proposito:
 * "origensPermitidas" aqui casa com "origens-permitidas" no YAML. Java usa
 * camelCase, arquivo de configuracao usa kebab-case, e o Spring faz a ponte.
 *
 * Este e o mecanismo pelo qual a lista de alvos monitorados vai sair do codigo
 * mais adiante -- hoje ela ainda esta fixa no CatalogoDeSistemas, que e o
 * combinado do passo 1.
 */
@ConfigurationProperties(prefix = "sentinela")
public record PropriedadesDoSentinela(List<String> origensPermitidas) {

    public PropriedadesDoSentinela {
        origensPermitidas = origensPermitidas == null ? List.of() : List.copyOf(origensPermitidas);
    }
}

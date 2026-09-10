package com.sentinela.api.config;

import com.sentinela.api.sistema.SistemaMonitorado;
import java.time.ZoneId;
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
 * A lista de sistemas monitorados chegou aqui vindo do CatalogoDeSistemas, onde
 * ficou fixa no codigo durante o passo 1. Ela precisou sair de la para a
 * instancia privada poder declarar os sistemas reais no seu proprio
 * application-{perfil}.yml, que nunca e versionado.
 */
@ConfigurationProperties(prefix = "sentinela")
public record PropriedadesDoSentinela(
        List<String> origensPermitidas,
        List<AplicacaoPublicadora> aplicacoes,
        List<SistemaMonitorado> sistemas,
        String fusoHorario) {

    private static final String FUSO_PADRAO = "America/Sao_Paulo";

    public PropriedadesDoSentinela {
        origensPermitidas = origensPermitidas == null ? List.of() : List.copyOf(origensPermitidas);
        aplicacoes = aplicacoes == null ? List.of() : List.copyOf(aplicacoes);
        // Lista vazia e estado valido: um sistema pode publicar evento sem ser
        // verificado quanto a disponibilidade, e vice-versa.
        sistemas = sistemas == null ? List.of() : List.copyOf(sistemas);
        fusoHorario = (fusoHorario == null || fusoHorario.isBlank()) ? FUSO_PADRAO : fusoHorario;
        ZoneId.of(fusoHorario); // fuso invalido derruba a subida, e nao a primeira requisicao
    }

    /**
     * O fuso em que "hoje" e contado.
     *
     * Sem isto o dia viraria a meia-noite de Londres, e a tela zeraria a
     * contagem as nove da noite -- no meio do expediente de quem opera o
     * sistema. Data nao existe sem fuso; guardar Instant e decidir o fuso na
     * hora de agrupar e o que mantem os dois corretos.
     */
    public ZoneId zona() {
        return ZoneId.of(fusoHorario);
    }
}

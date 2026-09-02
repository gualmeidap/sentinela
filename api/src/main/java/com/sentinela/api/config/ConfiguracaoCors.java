package com.sentinela.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Diz ao navegador quais paginas podem ler esta API.
 *
 * Sem isto, a pagina do painel nao consegue nada: o navegador aplica a politica
 * de mesma origem e recusa entregar ao JavaScript a resposta de um endereco
 * diferente daquele de onde a pagina veio. O curl nunca reclamou porque essa
 * regra e do navegador, nao do protocolo.
 *
 * Nao e uma restricao artificial do ambiente local: na arquitetura final a
 * pagina fica no S3/CloudFront e a API na EC2, dominios diferentes de forma
 * permanente.
 *
 * A lista de origens e fechada e vem de configuracao. O atalho comum aqui e
 * liberar "*", e ele e ruim por um motivo concreto: a instancia privada fica
 * numa rede acessivel de dentro da instituicao, e "*" deixaria qualquer pagina
 * que um funcionario abrisse no navegador ler o painel interno.
 *
 * Apenas GET e permitido -- a API de leitura nao tem por que aceitar escrita
 * vinda de navegador.
 *
 * @EnableConfigurationProperties aqui, e nao na classe principal: assim esta
 * configuracao carrega junto as propriedades de que depende, em qualquer
 * contexto onde entre. Um teste de fatia (@WebMvcTest) sobe so a camada web e
 * ignora a varredura global de propriedades -- declarar a dependencia no lugar
 * que a usa e o que faz a classe funcionar sozinha.
 */
@Configuration
@EnableConfigurationProperties(PropriedadesDoSentinela.class)
public class ConfiguracaoCors implements WebMvcConfigurer {

    private final PropriedadesDoSentinela propriedades;

    public ConfiguracaoCors(PropriedadesDoSentinela propriedades) {
        this.propriedades = propriedades;
    }

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        registro.addMapping("/**")
                .allowedOrigins(propriedades.origensPermitidas().toArray(String[]::new))
                .allowedMethods("GET")
                .allowCredentials(false);
    }
}

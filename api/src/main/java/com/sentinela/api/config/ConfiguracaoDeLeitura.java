package com.sentinela.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Liga a chave de leitura quando a instancia tem uma.
 *
 * A regra e "tudo exige a chave, menos o que esta listado aqui", e nao "so
 * estes caminhos exigem": um endpoint novo nasce protegido, e esquece-lo na
 * lista deixa a API fechada demais -- erro que aparece na hora -- em vez de
 * aberta demais, que nao aparece nunca.
 *
 * - /ping: o adaptador do Lambda chama antes de aceitar trafego, sem chave.
 * - /eventos: a escrita tem a chave propria de cada aplicacao (X-Chave-Aplicacao).
 * - /error: a pagina de erro interna do Spring; nao carrega dado.
 */
@Configuration
@EnableConfigurationProperties(PropriedadesDeLeitura.class)
public class ConfiguracaoDeLeitura implements WebMvcConfigurer {

    private final PropriedadesDeLeitura propriedades;

    public ConfiguracaoDeLeitura(PropriedadesDeLeitura propriedades) {
        this.propriedades = propriedades;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registro) {
        if (propriedades.protegida()) {
            registro.addInterceptor(new LeituraComChave(propriedades.chave()))
                    .addPathPatterns("/**")
                    .excludePathPatterns("/ping", "/eventos", "/error");
        }
    }
}

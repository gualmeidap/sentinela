package com.sentinela.api;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Ponto de entrada da API.
 *
 * A anotacao @SpringBootApplication liga tres coisas de uma vez:
 *  - varredura de componentes a partir deste pacote (com.sentinela.api) para baixo;
 *  - autoconfiguracao (ver spring-boot-starter-web no classpath e subir o Tomcat);
 *  - registro desta classe como fonte de configuracao.
 */
@SpringBootApplication
public class SentinelaApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelaApiApplication.class, args);
    }

    /**
     * O relogio da aplicacao, publicado como bean para poder ser trocado no
     * teste. Espalhar Instant.now() pelo codigo tornaria impossivel verificar
     * "o que a fita mostra as 3h da manha" sem mexer no relogio da maquina.
     */
    @Bean
    public Clock relogio() {
        return Clock.systemUTC();
    }
}

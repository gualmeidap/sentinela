package com.sentinela.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
}

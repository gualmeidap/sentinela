package com.sentinela.api.config;

import com.sentinela.api.disponibilidade.RepositorioDeVerificacoes;
import com.sentinela.api.disponibilidade.RepositorioDeVerificacoesDynamo;
import com.sentinela.api.eventos.RepositorioDeEventos;
import com.sentinela.api.eventos.RepositorioDeEventosDynamo;
import com.sentinela.api.persistencia.CriadorDeTabelas;
import com.sentinela.api.persistencia.FabricaDeClienteDynamo;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Liga a aplicacao ao DynamoDB. Ativa com o perfil "dynamo".
 *
 * Sem esse perfil, valem os repositorios em memoria e nada aqui entra no
 * contexto -- nem o cliente do SDK. E o que permite rodar a aplicacao, e a
 * suite inteira, sem banco nenhum por perto.
 *
 * Repare que o que muda e so qual implementacao da interface e registrada.
 * Servico e controller nao sabem, e nao precisam saber, se por baixo ha um mapa
 * na memoria ou um banco na AWS.
 */
@Configuration
@Profile("dynamo")
@EnableConfigurationProperties(PropriedadesDoDynamo.class)
public class ConfiguracaoDynamo {

    /**
     * destroyMethod = "close": o cliente mantem conexoes abertas, e o Spring
     * precisa fecha-las ao derrubar a aplicacao.
     */
    @Bean(destroyMethod = "close")
    public DynamoDbClient clienteDynamo(PropriedadesDoDynamo propriedades) {
        return FabricaDeClienteDynamo.criar(propriedades);
    }

    /**
     * Cria as tabelas na subida, quando configurado.
     *
     * Conveniente no ambiente local, onde o DynamoDB sobe vazio a cada
     * "docker compose up". Na AWS o normal e a tabela ser criada uma vez, por
     * infraestrutura, e a aplicacao nao ter permissao para criar nada -- por
     * isso e uma opcao, e nao comportamento fixo.
     */
    @Bean
    public CriadorDeTabelas criadorDeTabelas(DynamoDbClient cliente, PropriedadesDoDynamo propriedades) {
        CriadorDeTabelas criador = new CriadorDeTabelas(cliente, propriedades);
        if (propriedades.criarTabelas()) {
            criador.criarSeNecessario();
        }
        return criador;
    }

    // @DependsOn garante que as tabelas existam antes de qualquer coisa tentar
    // gravar nelas. Sem isso, o semeador de dados sinteticos poderia rodar
    // primeiro e falhar contra uma tabela que ainda nao existe.
    @Bean
    @DependsOn("criadorDeTabelas")
    public RepositorioDeVerificacoes repositorioDeVerificacoes(DynamoDbClient cliente,
                                                               PropriedadesDoDynamo propriedades) {
        return new RepositorioDeVerificacoesDynamo(cliente, propriedades);
    }

    @Bean
    @DependsOn("criadorDeTabelas")
    public RepositorioDeEventos repositorioDeEventos(DynamoDbClient cliente,
                                                     PropriedadesDoDynamo propriedades) {
        return new RepositorioDeEventosDynamo(cliente, propriedades);
    }
}

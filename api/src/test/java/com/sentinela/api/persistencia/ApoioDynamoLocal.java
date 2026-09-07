package com.sentinela.api.persistencia;

import com.sentinela.api.config.PropriedadesDoDynamo;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Apoio para os testes que falam com o DynamoDB Local.
 *
 * Sobe com "docker compose up -d" na raiz do repositorio. Se ele nao estiver
 * no ar, os testes que dependem dele sao PULADOS em vez de falharem: quem
 * clonou o projeto para so ler o codigo nao deve ver a suite vermelha por nao
 * ter Docker.
 *
 * No CI o container e declarado no workflow, entao la eles sempre rodam de
 * verdade -- que e o que impede o "pula se nao tiver" de virar desculpa para
 * nunca testar.
 */
public final class ApoioDynamoLocal {

    public static final String ENDPOINT = "http://localhost:8000";
    private static final int PORTA = 8000;

    private ApoioDynamoLocal() {
    }

    public static boolean disponivel() {
        try (Socket tomada = new Socket()) {
            tomada.connect(new InetSocketAddress("localhost", PORTA), 500);
            return true;
        } catch (IOException foraDoAr) {
            return false;
        }
    }

    /**
     * Propriedades apontando para o DynamoDB Local, com nomes de tabela unicos.
     *
     * Cada teste ganha as proprias tabelas. Compartilhar tabela entre testes
     * cria dependencia de ordem: um passa sozinho e falha na suite completa,
     * por causa do que outro deixou gravado.
     */
    public static PropriedadesDoDynamo propriedades() {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        return new PropriedadesDoDynamo(
                ENDPOINT,
                "us-east-1",
                "teste-verificacoes-" + sufixo,
                "teste-eventos-" + sufixo,
                30,
                true);
    }

    public static DynamoDbClient clienteCom(PropriedadesDoDynamo propriedades) {
        DynamoDbClient cliente = FabricaDeClienteDynamo.criar(propriedades);
        new CriadorDeTabelas(cliente, propriedades).criarSeNecessario();
        return cliente;
    }
}

package com.sentinela.api.persistencia;

import com.sentinela.api.config.PropriedadesDoDynamo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.TimeToLiveSpecification;
import software.amazon.awssdk.services.dynamodb.model.UpdateTimeToLiveRequest;

/**
 * Cria as duas tabelas, se ainda nao existirem.
 *
 * MODELAGEM
 *
 * O DynamoDB nao e relacional: a tabela e desenhada em funcao das consultas,
 * nao dos dados. Toda pergunta do painel tem a forma "do sistema X, entre tal e
 * tal hora", e as chaves saem direto dai:
 *
 *   particao (HASH)  = o sistema      -> toda consulta sabe de qual se trata
 *   ordenacao (RANGE) = o momento     -> permite buscar por faixa de tempo
 *
 * Com isso nenhuma consulta precisa varrer a tabela. Varredura e o que fica
 * lento quando o volume cresce e o que faz a conta subir.
 *
 * DUAS TABELAS, E NAO UMA
 *
 * Existe uma tecnica chamada single-table design que junta tudo numa tabela so.
 * Ela vale quando uma unica consulta precisa trazer entidades de tipos
 * diferentes de uma vez. Aqui disponibilidade e eventos sao sempre consultados
 * separadamente, entao ela cobraria complexidade sem entregar nada.
 *
 * CHAVES DE ORDENACAO DIFERENTES, DE PROPOSITO
 *
 * Verificacao usa o momento puro. Se o coletor rodar duas vezes no mesmo
 * instante, a segunda escrita sobrescreve a primeira -- e isso e desejavel: a
 * gravacao vira idempotente de graca, sem codigo de deduplicacao.
 *
 * Evento usa "momento#identificador". Dois eventos podem acontecer no mesmo
 * milissegundo, e ali sobrescrever seria perder dado de verdade.
 *
 * CAPACIDADE
 *
 * Modo provisionado, e nao sob demanda: o Always Free da AWS cobre 25 unidades
 * de leitura e 25 de escrita provisionadas, enquanto o modo sob demanda e
 * cobrado por requisicao. Com 5 de cada por tabela sobra folga larga para esta
 * carga (algumas escritas por minuto) e o total fica bem abaixo do limite
 * gratuito.
 */
public class CriadorDeTabelas {

    private static final Logger log = LoggerFactory.getLogger(CriadorDeTabelas.class);

    public static final String PARTICAO_VERIFICACAO = "sistemaId";
    public static final String ORDENACAO_VERIFICACAO = "momento";
    public static final String PARTICAO_EVENTO = "sistema";
    public static final String ORDENACAO_EVENTO = "ordenacao";
    public static final String ATRIBUTO_DE_EXPIRACAO = "expiraEm";

    private static final long CAPACIDADE = 5L;

    private final DynamoDbClient cliente;
    private final PropriedadesDoDynamo propriedades;

    public CriadorDeTabelas(DynamoDbClient cliente, PropriedadesDoDynamo propriedades) {
        this.cliente = cliente;
        this.propriedades = propriedades;
    }

    public void criarSeNecessario() {
        criar(propriedades.tabelaVerificacoes(), PARTICAO_VERIFICACAO, ORDENACAO_VERIFICACAO);
        criar(propriedades.tabelaEventos(), PARTICAO_EVENTO, ORDENACAO_EVENTO);
    }

    private void criar(String tabela, String particao, String ordenacao) {
        if (existe(tabela)) {
            log.info("tabela {} ja existe", tabela);
            ligarExpiracaoAutomatica(tabela);
            return;
        }
        try {
            cliente.createTable(CreateTableRequest.builder()
                    .tableName(tabela)
                    .keySchema(
                            KeySchemaElement.builder().attributeName(particao).keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName(ordenacao).keyType(KeyType.RANGE).build())
                    // So os atributos que fazem parte da chave sao declarados.
                    // O resto do item e livre: e o que significa dizer que o
                    // DynamoDB nao tem esquema fixo.
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName(particao)
                                    .attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName(ordenacao)
                                    .attributeType(ScalarAttributeType.S).build())
                    .billingMode(BillingMode.PROVISIONED)
                    .provisionedThroughput(ProvisionedThroughput.builder()
                            .readCapacityUnits(CAPACIDADE)
                            .writeCapacityUnits(CAPACIDADE)
                            .build())
                    .build());

            cliente.waiter().waitUntilTableExists(pedido -> pedido.tableName(tabela));
            log.info("tabela {} criada", tabela);
            ligarExpiracaoAutomatica(tabela);

        } catch (ResourceInUseException jaCriada) {
            // Duas instancias subindo ao mesmo tempo podem tentar criar a mesma
            // tabela. Perder essa corrida nao e erro: o que se queria ja existe.
            log.info("tabela {} foi criada por outra instancia", tabela);
        }
    }

    private boolean existe(String tabela) {
        try {
            cliente.describeTable(pedido -> pedido.tableName(tabela));
            return true;
        } catch (ResourceNotFoundException naoExiste) {
            return false;
        }
    }

    /**
     * Liga o TTL: o DynamoDB apaga sozinho os itens cujo atributo de expiracao
     * ja passou, sem rotina de limpeza para escrever nem manter.
     *
     * A exclusao nao e instantanea -- a AWS promete "em ate 48 horas" --, o que
     * nao incomoda aqui: o painel so olha as ultimas 24h e o dia corrente.
     */
    private void ligarExpiracaoAutomatica(String tabela) {
        try {
            cliente.updateTimeToLive(UpdateTimeToLiveRequest.builder()
                    .tableName(tabela)
                    .timeToLiveSpecification(TimeToLiveSpecification.builder()
                            .enabled(true)
                            .attributeName(ATRIBUTO_DE_EXPIRACAO)
                            .build())
                    .build());
            log.info("expiracao automatica ligada em {} pelo atributo {}", tabela, ATRIBUTO_DE_EXPIRACAO);
        } catch (RuntimeException jaEstaLigado) {
            // Pedir para ligar o que ja esta ligado devolve erro. Nao ha nada a
            // corrigir nesse caso.
            log.debug("expiracao automatica em {} ja estava configurada", tabela);
        }
    }
}

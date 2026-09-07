package com.sentinela.coletor.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.sentinela.coletor.Coletor;
import com.sentinela.coletor.Configuracao;
import com.sentinela.coletor.DestinoDynamo;
import com.sentinela.coletor.RegistroCombinado;
import com.sentinela.coletor.RegistroNoDynamo;
import com.sentinela.coletor.SaidaNoConsole;
import com.sentinela.coletor.Verificacao;
import com.sentinela.coletor.VerificadorHttp;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Ponto de entrada quando o coletor roda como funcao Lambda.
 *
 * O EventBridge chama isto a cada 5 minutos. O objeto de evento e ignorado: um
 * disparo agendado nao carrega nada que o coletor precise saber, ele so diz que
 * chegou a hora.
 *
 * COMO A LAMBDA REAPROVEITA O CONTAINER
 *
 * A AWS nao cria um processo novo a cada invocacao. Ela mantem o container vivo
 * por alguns minutos e reusa: o construtor roda UMA vez, o handleRequest roda a
 * cada disparo. E por isso que a configuracao e o cliente do DynamoDB sao
 * montados aqui no construtor.
 *
 * Montar o cliente dentro do handleRequest funcionaria e seria um desperdicio
 * caro: cada invocacao pagaria de novo a leitura de credencial, a resolucao do
 * endpoint e a abertura de conexao -- tudo isso em tempo cobrado, a cada 5
 * minutos, para sempre.
 *
 * O preco desse desenho e que erro de configuracao derruba a funcao no cold
 * start, e nao na primeira requisicao. Aqui isso e vantagem: falha na subida
 * aparece no CloudWatch imediatamente, em vez de silenciosamente a cada disparo.
 */
public class ManipuladorLambda implements RequestHandler<Object, String> {

    private final Configuracao configuracao;
    private final Coletor coletor;

    /**
     * Roda uma vez por container, no cold start.
     *
     * Repare que nao ha "close" do cliente em lugar nenhum: ele precisa
     * sobreviver entre invocacoes. Quem o encerra e a AWS, ao descartar o
     * container.
     */
    public ManipuladorLambda() {
        this(System.getenv(), Clock.systemUTC());
    }

    /** Construtor para teste: o ambiente e o relogio entram de fora. */
    ManipuladorLambda(Map<String, String> ambiente, Clock relogio) {
        // Sem argumentos de linha de comando: na Lambda a lista de alvos vem da
        // variavel SENTINELA_ALVOS, porque nao ha disco onde por arquivo.
        this.configuracao = Configuracao.de(new String[0], ambiente);

        DestinoDynamo destino = DestinoDynamo.doAmbiente(ambiente).orElseThrow(() ->
                new IllegalStateException(
                        "a funcao precisa de " + DestinoDynamo.VARIAVEL_TABELA
                                + ": sem tabela, a rodada nao teria onde ser gravada"));

        this.coletor = new Coletor(
                new VerificadorHttp(configuracao.tempoLimite(), relogio),
                new RegistroCombinado(
                        // A saida padrao vira CloudWatch Logs. Manter a impressao
                        // permite ver o que a funcao mediu sem consultar tabela.
                        new SaidaNoConsole(System.out, ZoneOffset.UTC),
                        new RegistroNoDynamo(destino)),
                relogio);
    }

    @Override
    public String handleRequest(Object eventoAgendado, Context contexto) {
        List<Verificacao> resultados = coletor.executar(configuracao.alvos());

        long fora = resultados.stream().filter(verificacao -> !verificacao.respondeu()).count();
        String resumo = resultados.size() + " alvo(s) verificado(s), " + fora + " fora do ar";

        // O retorno de uma funcao agendada nao vai para ninguem -- o EventBridge
        // descarta. Ele existe para aparecer no CloudWatch e para o teste de
        // invocacao manual mostrar algo util.
        contexto.getLogger().log(resumo);
        return resumo;
    }
}

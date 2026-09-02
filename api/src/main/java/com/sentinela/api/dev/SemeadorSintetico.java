package com.sentinela.api.dev;

import com.sentinela.api.config.AplicacaoPublicadora;
import com.sentinela.api.config.PropriedadesDoSentinela;
import com.sentinela.api.disponibilidade.RepositorioDeVerificacoes;
import com.sentinela.api.disponibilidade.Verificacao;
import com.sentinela.api.eventos.CatalogoDeAplicacoes;
import com.sentinela.api.eventos.Evento;
import com.sentinela.api.eventos.RepositorioDeEventos;
import com.sentinela.api.eventos.Resultado;
import com.sentinela.api.sistema.CatalogoDeSistemas;
import com.sentinela.api.sistema.SistemaMonitorado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Preenche o banco em memoria com dados sinteticos na subida: 24h de
 * verificacoes e os eventos do dia.
 *
 * Quem gera isso de verdade e o coletor (passo 5) e os proprios sistemas
 * publicando (passo 7). Sem o semeador, os endpoints do passo 1 e 3
 * responderiam corretamente uma tela vazia -- e nao daria para conferir se a
 * fita, o percentual e a contagem estao certos.
 *
 * @Profile("local") garante que isto so roda no perfil local. Numa instancia de
 * verdade a classe nem entra no contexto: dado sintetico misturado com medicao
 * real seria pior do que nao ter dado nenhum.
 *
 * Os codigos dos eventos vem do CatalogoDeAplicacoes, e nao de constantes aqui.
 * Assim o dado de demonstracao nao consegue divergir da lista fechada que a API
 * aceita de verdade -- se um codigo sair da configuracao, ele some da demo
 * junto.
 *
 * A semente do Random e fixa para a demo nao mudar a cada reinicio.
 */
@Component
@Profile("local")
public class SemeadorSintetico implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SemeadorSintetico.class);

    private static final Duration JANELA_DE_VERIFICACAO = Duration.ofHours(24);
    private static final Duration INTERVALO_DE_VERIFICACAO = Duration.ofMinutes(5);
    private static final Duration INTERVALO_DE_EVENTO = Duration.ofMinutes(12);
    private static final int PERCENTUAL_DE_FALHA_ALEATORIA = 2;
    private static final int PERCENTUAL_DE_FALHA_EM_EVENTO = 12;
    private static final String SISTEMA_COM_QUEDA = "api-integracao";

    private final CatalogoDeSistemas sistemas;
    private final CatalogoDeAplicacoes aplicacoes;
    private final RepositorioDeVerificacoes verificacoes;
    private final RepositorioDeEventos eventos;
    private final Clock relogio;
    private final ZoneId zona;

    public SemeadorSintetico(CatalogoDeSistemas sistemas, CatalogoDeAplicacoes aplicacoes,
                             RepositorioDeVerificacoes verificacoes, RepositorioDeEventos eventos,
                             Clock relogio, PropriedadesDoSentinela propriedades) {
        this.sistemas = sistemas;
        this.aplicacoes = aplicacoes;
        this.verificacoes = verificacoes;
        this.eventos = eventos;
        this.relogio = relogio;
        this.zona = propriedades.zona();
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant agora = relogio.instant();
        Random sorteio = new Random(42);

        int quantasVerificacoes = semearVerificacoes(agora, sorteio);
        int quantosEventos = semearEventos(agora, sorteio);

        log.info("perfil local: {} verificacoes e {} eventos sinteticos gerados",
                quantasVerificacoes, quantosEventos);
    }

    private int semearVerificacoes(Instant agora, Random sorteio) {
        Instant inicio = agora.minus(JANELA_DE_VERIFICACAO);
        int gravadas = 0;

        for (SistemaMonitorado sistema : sistemas.todos()) {
            for (Instant momento = inicio; momento.isBefore(agora);
                 momento = momento.plus(INTERVALO_DE_VERIFICACAO)) {
                verificacoes.registrar(gerarVerificacao(sistema, momento, agora, sorteio));
                gravadas++;
            }
        }
        return gravadas;
    }

    /**
     * Um sistema leva uma queda de uma hora, entre 6h e 5h atras, para a fita
     * ter um trecho vermelho visivel e o percentual sair de 100. O resto sofre
     * falhas esparsas.
     */
    private Verificacao gerarVerificacao(SistemaMonitorado sistema, Instant momento, Instant agora,
                                         Random sorteio) {
        boolean dentroDaQueda = SISTEMA_COM_QUEDA.equals(sistema.id())
                && momento.isAfter(agora.minus(Duration.ofHours(6)))
                && momento.isBefore(agora.minus(Duration.ofHours(5)));

        if (dentroDaQueda || sorteio.nextInt(100) < PERCENTUAL_DE_FALHA_ALEATORIA) {
            return Verificacao.naoRespondeu(sistema.id(), momento);
        }
        return Verificacao.respondeuEm(sistema.id(), momento, 80 + sorteio.nextInt(240));
    }

    /** Eventos desde a meia-noite de hoje ate agora, para a contagem do dia ter conteudo. */
    private int semearEventos(Instant agora, Random sorteio) {
        Instant inicioDoDia = LocalDate.ofInstant(agora, zona).atStartOfDay(zona).toInstant();
        int gravados = 0;

        for (AplicacaoPublicadora aplicacao : aplicacoes.todas()) {
            if (aplicacao.tipos().isEmpty()) {
                continue;
            }
            for (Instant momento = inicioDoDia; momento.isBefore(agora);
                 momento = momento.plus(INTERVALO_DE_EVENTO)) {
                eventos.registrar(gerarEvento(aplicacao, momento, sorteio));
                gravados++;
            }
        }
        return gravados;
    }

    private Evento gerarEvento(AplicacaoPublicadora aplicacao, Instant momento, Random sorteio) {
        String tipo = sortear(aplicacao.tipos(), sorteio);
        Map<String, String> contexto = aplicacao.chavesDeContexto().isEmpty()
                ? Map.of()
                : Map.of(aplicacao.chavesDeContexto().get(0), "unidade_" + (1 + sorteio.nextInt(3)));

        boolean falhou = !aplicacao.motivos().isEmpty()
                && sorteio.nextInt(100) < PERCENTUAL_DE_FALHA_EM_EVENTO;

        if (falhou) {
            return new Evento(aplicacao.id(), tipo, Resultado.FALHA,
                    sortear(aplicacao.motivos(), sorteio), momento, contexto);
        }
        return new Evento(aplicacao.id(), tipo, Resultado.SUCESSO, null, momento, contexto);
    }

    private String sortear(List<String> opcoes, Random sorteio) {
        return opcoes.get(sorteio.nextInt(opcoes.size()));
    }
}

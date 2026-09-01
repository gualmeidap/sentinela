package com.sentinela.api.dev;

import com.sentinela.api.disponibilidade.RepositorioDeVerificacoes;
import com.sentinela.api.disponibilidade.Verificacao;
import com.sentinela.api.sistema.CatalogoDeSistemas;
import com.sentinela.api.sistema.SistemaMonitorado;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Preenche o banco em memoria com 24h de verificacoes sinteticas na subida.
 *
 * Quem gera verificacao de verdade e o coletor, que so existe no passo 5. Sem
 * isto, os endpoints do passo 1 responderiam corretamente uma tela vazia -- e
 * nao daria para ver se a fita e o percentual estao certos, nem construir a
 * pagina do passo 2.
 *
 * @Profile("local") garante que isto so roda no perfil local. Numa instancia de
 * verdade a classe nem entra no contexto: dado sintetico misturado com medicao
 * real seria pior do que nao ter dado nenhum.
 *
 * A semente do Random e fixa para a demo nao mudar a cada reinicio.
 */
@Component
@Profile("local")
public class SemeadorSintetico implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SemeadorSintetico.class);

    private static final Duration JANELA = Duration.ofHours(24);
    private static final Duration INTERVALO = Duration.ofMinutes(5);
    private static final int PERCENTUAL_DE_FALHA_ALEATORIA = 2;
    private static final String SISTEMA_COM_QUEDA = "api-integracao";

    private final CatalogoDeSistemas catalogo;
    private final RepositorioDeVerificacoes repositorio;
    private final Clock relogio;

    public SemeadorSintetico(CatalogoDeSistemas catalogo, RepositorioDeVerificacoes repositorio, Clock relogio) {
        this.catalogo = catalogo;
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant agora = relogio.instant();
        Instant inicio = agora.minus(JANELA);
        Random sorteio = new Random(42);
        int gravadas = 0;

        for (SistemaMonitorado sistema : catalogo.todos()) {
            for (Instant momento = inicio; momento.isBefore(agora); momento = momento.plus(INTERVALO)) {
                repositorio.registrar(gerar(sistema, momento, agora, sorteio));
                gravadas++;
            }
        }

        log.info("perfil local: {} verificacoes sinteticas geradas para {} sistemas",
                gravadas, catalogo.todos().size());
    }

    /**
     * Um sistema leva uma queda de uma hora, entre 6h e 5h atras, para a fita
     * ter um trecho vermelho visivel e o percentual sair de 100. O resto sofre
     * falhas esparsas.
     */
    private Verificacao gerar(SistemaMonitorado sistema, Instant momento, Instant agora, Random sorteio) {
        boolean dentroDaQueda = SISTEMA_COM_QUEDA.equals(sistema.id())
                && momento.isAfter(agora.minus(Duration.ofHours(6)))
                && momento.isBefore(agora.minus(Duration.ofHours(5)));

        if (dentroDaQueda || sorteio.nextInt(100) < PERCENTUAL_DE_FALHA_ALEATORIA) {
            return Verificacao.naoRespondeu(sistema.id(), momento);
        }
        return Verificacao.respondeuEm(sistema.id(), momento, 80 + sorteio.nextInt(240));
    }
}

package com.sentinela.api.disponibilidade;

import com.sentinela.api.erro.SistemaNaoEncontrado;
import com.sentinela.api.sistema.CatalogoDeSistemas;
import com.sentinela.api.sistema.SistemaMonitorado;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Junta catalogo, repositorio e regra de calculo para responder as duas
 * perguntas da tela: como esta cada sistema agora, e como foi nas ultimas 24h.
 *
 * O Clock e injetado em vez de chamar Instant.now() direto no meio do codigo.
 * Isso permite congelar o tempo no teste -- e a mesma ideia do freezegun no
 * Python, so que sem monkey patch: e so passar outro Clock no construtor.
 *
 * O construtor recebe tudo o que a classe precisa e o Spring preenche sozinho.
 * Nao ha anotacao de injecao porque, havendo um unico construtor, o Spring o
 * usa por padrao.
 */
@Service
public class ConsultaDeDisponibilidade {

    private final CatalogoDeSistemas catalogo;
    private final RepositorioDeVerificacoes repositorio;
    private final Clock relogio;

    public ConsultaDeDisponibilidade(CatalogoDeSistemas catalogo, RepositorioDeVerificacoes repositorio,
                                     Clock relogio) {
        this.catalogo = catalogo;
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    public List<EstadoAtual> estadoDeTodos() {
        return catalogo.todos().stream()
                .map(sistema -> repositorio.ultima(sistema.id())
                        .map(ultima -> EstadoAtual.de(sistema, ultima))
                        .orElseGet(() -> EstadoAtual.semDado(sistema)))
                .toList();
    }

    /**
     * Fita das ultimas 24h de um sistema.
     *
     * A consulta ao repositorio pega uma janela deliberadamente maior que a
     * fita, um bloco para cada lado. A fita e alinhada no limite de bloco
     * seguinte a agora, entao ela comeca um pouco antes de "agora menos 24h";
     * buscar exatamente 24h deixaria o primeiro bloco furado. A calculadora
     * descarta o que sobra.
     */
    public Fita fitaDe(String sistemaId) {
        SistemaMonitorado sistema = catalogo.porId(sistemaId)
                .orElseThrow(() -> new SistemaNaoEncontrado(sistemaId));

        Instant agora = relogio.instant();
        Instant de = agora.minus(CalculadoraDeDisponibilidade.JANELA_PADRAO)
                .minus(CalculadoraDeDisponibilidade.BLOCO_PADRAO);
        Instant ate = agora.plus(CalculadoraDeDisponibilidade.BLOCO_PADRAO);

        List<Verificacao> verificacoes = repositorio.entre(sistema.id(), de, ate);
        return CalculadoraDeDisponibilidade.fita(verificacoes, agora);
    }

    public SistemaMonitorado sistema(String sistemaId) {
        return catalogo.porId(sistemaId).orElseThrow(() -> new SistemaNaoEncontrado(sistemaId));
    }
}

package com.sentinela.api.eventos;

import com.sentinela.api.config.AplicacaoPublicadora;
import com.sentinela.api.erro.ChaveInvalida;
import com.sentinela.api.erro.EventoRecusado;
import com.sentinela.api.erro.LimiteExcedido;
import com.sentinela.api.erro.PublicacaoNaoAutorizada;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * A porta de entrada dos eventos, e a unica coisa entre um sistema externo e o
 * banco do portal.
 *
 * A ordem das checagens e deliberada: primeiro quem e (chave), depois se pode
 * falar por aquele sistema, depois se o conteudo respeita o contrato, e so
 * entao o limite de volume. Contar contra o limite antes de validar deixaria um
 * publicador com bug consumir a cota inteira mandando lixo.
 */
@Service
public class RecepcaoDeEventos {

    /**
     * Valor de contexto: codigo curto e minusculo, sem espaco e sem arroba.
     *
     * Este padrao e o que da dentes a regra de dado pessoal. "campus" e
     * "fornecedorTipo" sao contexto legitimo; nome com espaco, e-mail e CPF
     * formatado nao passam pelo filtro. E uma barreira estrutural, nao um
     * pedido de boa vontade ao publicador.
     */
    private static final Pattern VALOR_DE_CONTEXTO = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,39}$");
    private static final int MAXIMO_DE_CAMPOS_DE_CONTEXTO = 10;

    /** Folga para relogio do publicador adiantado. */
    private static final Duration TOLERANCIA_DE_RELOGIO = Duration.ofMinutes(5);
    /** Mesma janela do TTL previsto para o DynamoDB. */
    private static final Duration IDADE_MAXIMA = Duration.ofDays(30);

    private final CatalogoDeAplicacoes aplicacoes;
    private final LimitadorDeEscrita limitador;
    private final RepositorioDeEventos repositorio;
    private final Clock relogio;

    public RecepcaoDeEventos(CatalogoDeAplicacoes aplicacoes, LimitadorDeEscrita limitador,
                             RepositorioDeEventos repositorio, Clock relogio) {
        this.aplicacoes = aplicacoes;
        this.limitador = limitador;
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    public Evento receber(String chaveApresentada, EventoRecebido recebido) {
        AplicacaoPublicadora aplicacao = aplicacoes.porChave(chaveApresentada)
                .orElseThrow(ChaveInvalida::new);

        if (recebido == null) {
            throw new EventoRecusado("corpo_ausente", "O corpo do evento e obrigatorio.");
        }
        if (recebido.sistema() == null || recebido.sistema().isBlank()) {
            throw new EventoRecusado("sistema_ausente", "O campo sistema e obrigatorio.");
        }
        if (!aplicacao.id().equals(recebido.sistema())) {
            throw new PublicacaoNaoAutorizada();
        }

        String tipo = exigirTipo(aplicacao, recebido.tipo());
        Resultado resultado = exigirResultado(recebido.resultado());
        String motivo = exigirMotivo(aplicacao, resultado, recebido.motivo());
        Instant ocorridoEm = exigirMomento(recebido.ocorridoEm());
        Map<String, String> contexto = exigirContexto(aplicacao, recebido.contexto());

        if (!limitador.cabeNoLimite(aplicacao.id(), aplicacao.limitePorHora())) {
            throw new LimiteExcedido(aplicacao.limitePorHora());
        }

        Evento evento = new Evento(aplicacao.id(), tipo, resultado, motivo, ocorridoEm, contexto);
        repositorio.registrar(evento);
        return evento;
    }

    private String exigirTipo(AplicacaoPublicadora aplicacao, String tipo) {
        if (tipo == null || tipo.isBlank()) {
            throw new EventoRecusado("tipo_ausente", "O campo tipo e obrigatorio.");
        }
        if (!aplicacao.aceitaTipo(tipo)) {
            throw new EventoRecusado("tipo_desconhecido",
                    "O tipo informado nao esta na lista declarada para esta aplicacao.");
        }
        return tipo;
    }

    private Resultado exigirResultado(String texto) {
        Resultado resultado = Resultado.deTexto(texto);
        if (resultado == null) {
            throw new EventoRecusado("resultado_invalido", "O resultado deve ser sucesso ou falha.");
        }
        return resultado;
    }

    private String exigirMotivo(AplicacaoPublicadora aplicacao, Resultado resultado, String motivo) {
        boolean informado = motivo != null && !motivo.isBlank();

        if (resultado == Resultado.SUCESSO) {
            if (informado) {
                throw new EventoRecusado("motivo_em_sucesso", "Evento de sucesso nao leva motivo.");
            }
            return null;
        }
        if (!informado) {
            throw new EventoRecusado("motivo_ausente", "Evento de falha precisa de motivo.");
        }
        if (!aplicacao.aceitaMotivo(motivo)) {
            throw new EventoRecusado("motivo_desconhecido",
                    "O motivo informado nao esta na lista declarada para esta aplicacao.");
        }
        return motivo;
    }

    /**
     * O momento vem do publicador, porque o evento aconteceu la e nao aqui. Mas
     * vem com limite: um relogio errado na origem jogaria eventos para fora da
     * janela do dia e a contagem da tela passaria a mentir.
     */
    private Instant exigirMomento(Instant ocorridoEm) {
        if (ocorridoEm == null) {
            throw new EventoRecusado("ocorrido_em_ausente", "O campo ocorridoEm e obrigatorio.");
        }
        Instant agora = relogio.instant();
        if (ocorridoEm.isAfter(agora.plus(TOLERANCIA_DE_RELOGIO))) {
            throw new EventoRecusado("ocorrido_em_no_futuro", "O evento esta no futuro.");
        }
        if (ocorridoEm.isBefore(agora.minus(IDADE_MAXIMA))) {
            throw new EventoRecusado("ocorrido_em_muito_antigo",
                    "O evento e mais antigo que a janela de retencao do portal.");
        }
        return ocorridoEm;
    }

    private Map<String, String> exigirContexto(AplicacaoPublicadora aplicacao, Map<String, String> contexto) {
        if (contexto == null || contexto.isEmpty()) {
            return Map.of();
        }
        if (contexto.size() > MAXIMO_DE_CAMPOS_DE_CONTEXTO) {
            throw new EventoRecusado("contexto_extenso", "Campos de contexto demais no evento.");
        }

        Map<String, String> limpo = new LinkedHashMap<>();
        for (Map.Entry<String, String> campo : contexto.entrySet()) {
            if (!aplicacao.aceitaChaveDeContexto(campo.getKey())) {
                throw new EventoRecusado("contexto_nao_declarado",
                        "Ha campo de contexto que nao foi declarado para esta aplicacao.");
            }
            if (campo.getValue() == null || !VALOR_DE_CONTEXTO.matcher(campo.getValue()).matches()) {
                throw new EventoRecusado("contexto_invalido",
                        "Valor de contexto deve ser um codigo curto em minusculas, sem espaco.");
            }
            limpo.put(campo.getKey(), campo.getValue());
        }
        return limpo;
    }
}

package com.sentinela.api.disponibilidade;

import com.sentinela.api.sistema.SistemaMonitorado;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Os endpoints de disponibilidade.
 *
 * Os records aninhados sao DTOs: a forma do JSON que sai na resposta. Existem
 * separados do dominio de proposito -- mudar um campo do contrato publico da
 * API nao pode obrigar a mudar a regra de negocio, e mudar a regra nao pode
 * quebrar o cliente sem aviso.
 */
@RestController
@RequestMapping("/sistemas")
public class SistemasController {

    private final ConsultaDeDisponibilidade consulta;

    public SistemasController(ConsultaDeDisponibilidade consulta) {
        this.consulta = consulta;
    }

    /** Lista os sistemas monitorados com o estado de cada um agora. */
    @GetMapping
    public List<SistemaResposta> listar() {
        return consulta.estadoDeTodos().stream()
                .map(SistemaResposta::de)
                .toList();
    }

    /** Fita das ultimas 24h de um sistema, em blocos de 15 minutos. */
    @GetMapping("/{id}/disponibilidade")
    public DisponibilidadeResposta disponibilidade(@PathVariable String id) {
        SistemaMonitorado sistema = consulta.sistema(id);
        Fita fita = consulta.fitaDe(id);
        return DisponibilidadeResposta.de(sistema, fita);
    }

    public record SistemaResposta(String id, String nome, String url, EstadoAtual.Situacao situacao,
                                  Integer tempoRespostaMs, Instant ultimaVerificacao) {

        static SistemaResposta de(EstadoAtual estado) {
            return new SistemaResposta(
                    estado.sistema().id(),
                    estado.sistema().nome(),
                    estado.sistema().url().toString(),
                    estado.situacao(),
                    estado.tempoRespostaMs(),
                    estado.ultimaVerificacao());
        }
    }

    public record BlocoResposta(Instant inicio, Bloco.Estado estado, int verificacoes, int falhas) {

        static BlocoResposta de(Bloco bloco) {
            return new BlocoResposta(bloco.inicio(), bloco.estado(), bloco.verificacoes(), bloco.falhas());
        }
    }

    public record DisponibilidadeResposta(String sistemaId, String nome, Instant inicio, Instant fim,
                                          int janelaHoras, int blocoMinutos, Double percentual,
                                          int verificacoes, int falhas, List<BlocoResposta> blocos) {

        static DisponibilidadeResposta de(SistemaMonitorado sistema, Fita fita) {
            return new DisponibilidadeResposta(
                    sistema.id(),
                    sistema.nome(),
                    fita.inicio(),
                    fita.fim(),
                    (int) CalculadoraDeDisponibilidade.JANELA_PADRAO.toHours(),
                    (int) CalculadoraDeDisponibilidade.BLOCO_PADRAO.toMinutes(),
                    fita.percentual().isPresent() ? fita.percentual().getAsDouble() : null,
                    fita.verificacoes(),
                    fita.falhas(),
                    fita.blocos().stream().map(BlocoResposta::de).toList());
        }
    }
}

package com.sentinela.api.eventos;

import com.sentinela.api.sistema.SistemaMonitorado;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Os endpoints de evento: uma porta de escrita e uma de leitura.
 *
 * A resposta do POST e deliberadamente magra -- 201 e nada mais. Devolver o
 * evento gravado nao ajudaria o publicador, que ja tem os dados, e criaria um
 * caminho a mais por onde conteudo da requisicao volta para fora.
 */
@RestController
public class EventosController {

    public static final String CABECALHO_DA_CHAVE = "X-Chave-Aplicacao";

    private final RecepcaoDeEventos recepcao;
    private final ConsultaDeEventos consulta;

    public EventosController(RecepcaoDeEventos recepcao, ConsultaDeEventos consulta) {
        this.recepcao = recepcao;
        this.consulta = consulta;
    }

    @PostMapping("/eventos")
    @ResponseStatus(HttpStatus.CREATED)
    public void publicar(
            @RequestHeader(value = CABECALHO_DA_CHAVE, required = false) String chave,
            @RequestBody(required = false) EventoRecebido evento) {
        recepcao.receber(chave, evento);
    }

    @GetMapping("/sistemas/{id}/eventos")
    public EventosDoSistema porSistema(@PathVariable String id) {
        SistemaMonitorado sistema = consulta.sistema(id);
        ResumoDoDia resumo = consulta.resumoDeHoje(id);
        List<EventoResumido> ultimos = consulta.ultimos(id).stream().map(EventoResumido::de).toList();
        return new EventosDoSistema(sistema.id(), sistema.nome(), resumo, ultimos);
    }

    public record EventosDoSistema(String sistemaId, String nome, ResumoDoDia hoje,
                                   List<EventoResumido> ultimos) {
    }

    public record EventoResumido(Instant ocorridoEm, String tipo, Resultado resultado, String motivo,
                                 Map<String, String> contexto) {

        static EventoResumido de(Evento evento) {
            return new EventoResumido(evento.ocorridoEm(), evento.tipo(), evento.resultado(),
                    evento.motivo(), evento.contexto());
        }
    }
}

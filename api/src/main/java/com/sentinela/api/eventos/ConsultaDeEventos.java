package com.sentinela.api.eventos;

import com.sentinela.api.config.PropriedadesDoSentinela;
import com.sentinela.api.erro.SistemaNaoEncontrado;
import com.sentinela.api.sistema.CatalogoDeSistemas;
import com.sentinela.api.sistema.SistemaMonitorado;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * O lado de leitura dos eventos: o que a tela precisa saber.
 */
@Service
public class ConsultaDeEventos {

    public static final int ULTIMOS_PADRAO = 20;

    private final CatalogoDeSistemas catalogo;
    private final RepositorioDeEventos repositorio;
    private final Clock relogio;
    private final ZoneId zona;

    public ConsultaDeEventos(CatalogoDeSistemas catalogo, RepositorioDeEventos repositorio,
                             Clock relogio, PropriedadesDoSentinela propriedades) {
        this.catalogo = catalogo;
        this.repositorio = repositorio;
        this.relogio = relogio;
        this.zona = propriedades.zona();
    }

    public SistemaMonitorado sistema(String sistemaId) {
        return catalogo.porId(sistemaId).orElseThrow(() -> new SistemaNaoEncontrado(sistemaId));
    }

    /** A contagem de hoje, agrupada por tipo e resultado, com as falhas por motivo. */
    public ResumoDoDia resumoDeHoje(String sistemaId) {
        LocalDate hoje = LocalDate.ofInstant(relogio.instant(), zona);
        Instant inicio = hoje.atStartOfDay(zona).toInstant();
        Instant fim = hoje.plusDays(1).atStartOfDay(zona).toInstant();

        return AgregadorDeEventos.resumir(repositorio.entre(sistemaId, inicio, fim), hoje);
    }

    /** Os ultimos eventos do sistema, do mais recente para o mais antigo. */
    public List<Evento> ultimos(String sistemaId) {
        return repositorio.ultimos(sistemaId, ULTIMOS_PADRAO);
    }
}

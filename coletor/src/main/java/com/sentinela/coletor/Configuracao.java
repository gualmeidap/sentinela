package com.sentinela.coletor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * De onde vem a lista de alvos.
 *
 * A lista NUNCA sai do repositorio -- e a regra da instancia privada, e ela
 * vale aqui mais do que em qualquer outro lugar, porque e exatamente aqui que
 * as URLs internas apareceriam.
 *
 * Duas origens, uma para cada jeito de rodar:
 *
 *  - arquivo .properties passado como argumento, para rodar na sua maquina.
 *    O formato "id=url" e o proprio formato do java.util.Properties, entao a
 *    leitura sai de graca, sem parser nem dependencia.
 *
 *  - variavel de ambiente SENTINELA_ALVOS, para a Lambda -- que nao tem disco
 *    onde por arquivo de configuracao, so variavel de ambiente.
 */
public record Configuracao(List<Alvo> alvos, Duration tempoLimite) {

    public static final String VARIAVEL_DE_ALVOS = "SENTINELA_ALVOS";
    public static final String VARIAVEL_DE_TEMPO_LIMITE = "SENTINELA_TIMEOUT_MS";
    private static final Duration TEMPO_LIMITE_PADRAO = Duration.ofSeconds(5);

    public Configuracao {
        alvos = List.copyOf(alvos);
        if (alvos.isEmpty()) {
            throw new IllegalArgumentException(
                    "nenhum alvo configurado: passe um arquivo .properties como argumento ou defina "
                            + VARIAVEL_DE_ALVOS);
        }
        if (tempoLimite.isZero() || tempoLimite.isNegative()) {
            throw new IllegalArgumentException("tempo limite precisa ser positivo");
        }
    }

    public static Configuracao de(String[] argumentos, Map<String, String> ambiente) {
        List<Alvo> alvos = (argumentos != null && argumentos.length > 0)
                ? deArquivo(Path.of(argumentos[0]))
                : deTexto(ambiente.get(VARIAVEL_DE_ALVOS));

        return new Configuracao(alvos, tempoLimiteDe(ambiente.get(VARIAVEL_DE_TEMPO_LIMITE)));
    }

    /** Arquivo no formato "id=url", uma linha por alvo. */
    public static List<Alvo> deArquivo(Path caminho) {
        if (!Files.isReadable(caminho)) {
            throw new IllegalArgumentException("arquivo de alvos nao encontrado: " + caminho);
        }
        Properties propriedades = new Properties();
        try (var entrada = Files.newBufferedReader(caminho)) {
            propriedades.load(entrada);
        } catch (IOException falha) {
            throw new UncheckedIOException("nao foi possivel ler o arquivo de alvos: " + caminho, falha);
        }

        List<Alvo> alvos = new ArrayList<>();
        for (String id : propriedades.stringPropertyNames()) {
            alvos.add(montar(id, propriedades.getProperty(id)));
        }
        alvos.sort(java.util.Comparator.comparing(Alvo::id));
        return alvos;
    }

    /** Texto no formato "id=url;id=url", para variavel de ambiente. */
    public static List<Alvo> deTexto(String texto) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        List<Alvo> alvos = new ArrayList<>();
        for (String parte : texto.split(";")) {
            if (parte.isBlank()) {
                continue;
            }
            int igual = parte.indexOf('=');
            if (igual < 0) {
                throw new IllegalArgumentException(
                        "alvo fora do formato esperado id=url em " + VARIAVEL_DE_ALVOS);
            }
            alvos.add(montar(parte.substring(0, igual), parte.substring(igual + 1)));
        }
        return alvos;
    }

    private static Alvo montar(String id, String url) {
        try {
            return new Alvo(id.trim(), new URI(url.trim()));
        } catch (URISyntaxException malformada) {
            // A mensagem cita o id, nunca a url: mensagem de erro vai parar em
            // log, e log com URL interna e vazamento com carimbo de data.
            throw new IllegalArgumentException("url invalida para o alvo " + id.trim());
        }
    }

    private static Duration tempoLimiteDe(String valor) {
        if (valor == null || valor.isBlank()) {
            return TEMPO_LIMITE_PADRAO;
        }
        try {
            return Duration.ofMillis(Long.parseLong(valor.trim()));
        } catch (NumberFormatException naoENumero) {
            throw new IllegalArgumentException(VARIAVEL_DE_TEMPO_LIMITE + " precisa ser um numero em milissegundos");
        }
    }
}

'use strict';

/*
 * A pagina do painel.
 *
 * Sem framework, de proposito: o que vai para o S3 e arquivo estatico servido
 * direto pelo CloudFront. Nao ha build, nao ha node_modules, nao ha nada para
 * quebrar entre escrever e publicar.
 *
 * Toda a inteligencia -- calculo de fita, percentual, estado de bloco,
 * agregacao de evento por motivo -- ficou na API. A pagina so desenha o que
 * recebe. Se a regra estivesse aqui, ela precisaria ser reescrita e testada de
 * novo em qualquer outro consumidor.
 */

// Em producao isto vira o dominio da API na EC2.
const API = 'http://localhost:8080';
const INTERVALO_DE_ATUALIZACAO_MS = 30000;

const painel = document.getElementById('painel');
const aviso = document.getElementById('aviso');
const atualizacao = document.getElementById('atualizacao');

async function buscarJson(caminho) {
  const resposta = await fetch(API + caminho);
  if (!resposta.ok) {
    throw new Error('a API respondeu ' + resposta.status + ' em ' + caminho);
  }
  return resposta.json();
}

async function carregar() {
  try {
    const sistemas = await buscarJson('/sistemas');
    const caminho = (sistema, recurso) =>
      '/sistemas/' + encodeURIComponent(sistema.id) + '/' + recurso;

    // Promise.all dispara tudo em paralelo e espera o conjunto. Em serie, tres
    // sistemas custariam seis viagens somadas.
    const [fitas, eventos] = await Promise.all([
      Promise.all(sistemas.map((s) => buscarJson(caminho(s, 'disponibilidade')))),
      Promise.all(sistemas.map((s) => buscarJson(caminho(s, 'eventos')))),
    ]);

    desenhar(sistemas, fitas, eventos);
    esconderAviso();
    atualizacao.textContent = 'atualizado as ' + hora(new Date());
  } catch (erro) {
    mostrarAviso(erro.message);
  }
}

function desenhar(sistemas, fitas, eventos) {
  painel.replaceChildren(
    ...sistemas.map((sistema, i) => cartao(sistema, fitas[i], eventos[i]))
  );
}

function cartao(sistema, fita, eventos) {
  const artigo = elemento('article', 'cartao');

  const topo = elemento('div', 'cartao-topo');
  topo.append(
    elemento('span', 'indicador ' + classeDaSituacao(sistema.situacao)),
    identidade(sistema),
    numeros(sistema, fita)
  );

  artigo.append(topo, desenharFita(fita), eixo(fita), secaoDeEventos(eventos));
  return artigo;
}

function identidade(sistema) {
  const bloco = elemento('div', 'cartao-identidade');
  bloco.append(
    elemento('h2', null, sistema.nome),
    elemento('p', 'url', sistema.url)
  );
  return bloco;
}

function numeros(sistema, fita) {
  const bloco = elemento('div', 'cartao-numeros');
  const tempo = sistema.tempoRespostaMs === null || sistema.tempoRespostaMs === undefined
    ? '--'
    : sistema.tempoRespostaMs + ' ms';
  bloco.append(
    elemento('p', 'tempo', tempo),
    elemento('p', 'percentual', resumo(fita))
  );
  return bloco;
}

/*
 * percentual nulo nao e zero: significa que nao houve verificacao nenhuma na
 * janela. Escrever "0%" aqui anunciaria uma queda que ninguem mediu.
 */
function resumo(fita) {
  if (fita.percentual === null || fita.percentual === undefined) {
    return 'sem medicao em 24h';
  }
  return numero(fita.percentual) + '% em 24h';
}

function desenharFita(fita) {
  const faixa = elemento('div', 'fita');
  faixa.append(...fita.blocos.map(bloco));
  return faixa;
}

function bloco(dados) {
  const quadrado = elemento('div', 'bloco ' + dados.estado.toLowerCase().replace('_', '-'));
  quadrado.title = descricaoDoBloco(dados);
  return quadrado;
}

function descricaoDoBloco(dados) {
  const quando = hora(new Date(dados.inicio));
  if (dados.verificacoes === 0) {
    return quando + ' - sem verificacao';
  }
  const falhas = dados.falhas === 0
    ? 'nenhuma falha'
    : dados.falhas + (dados.falhas === 1 ? ' falha' : ' falhas');
  return quando + ' - ' + dados.verificacoes + ' verificacoes, ' + falhas;
}

function eixo(fita) {
  const linha = elemento('div', 'eixo');
  linha.append(
    elemento('span', null, hora(new Date(fita.inicio))),
    elemento('span', null, '12h atras'),
    elemento('span', null, 'agora')
  );
  return linha;
}

/*
 * A metade que diferencia o portal: o que o sistema fez, nao so se respondeu.
 *
 * Sistema sem evento nenhum recebe uma linha discreta em vez de secao vazia --
 * a maioria dos sistemas monitorados nunca vai publicar evento, e nao faz
 * sentido reservar espaco para isso em todos os cartoes.
 */
function secaoDeEventos(eventos) {
  const secao = elemento('section', 'eventos');
  const hoje = eventos.hoje;

  if (hoje.total === 0) {
    secao.append(elemento('p', 'eventos-vazio', 'Sem eventos publicados hoje.'));
    return secao;
  }

  const cabecalho = elemento('div', 'eventos-cabecalho');
  cabecalho.append(
    elemento('h3', null, 'Eventos de hoje'),
    contagemGeral(hoje)
  );
  secao.append(cabecalho, listaDeTipos(hoje.tipos));

  if (eventos.ultimos.length > 0) {
    secao.append(ultimosEventos(eventos.ultimos));
  }
  return secao;
}

function contagemGeral(hoje) {
  const linha = elemento('p', 'eventos-total');
  linha.append(
    elemento('strong', null, String(hoje.total)),
    elemento('span', null, ' eventos - '),
    elemento('strong', 'ok', String(hoje.sucessos)),
    elemento('span', null, ' sucesso, '),
    elemento('strong', hoje.falhas > 0 ? 'ruim' : null, String(hoje.falhas)),
    elemento('span', null, ' falha')
  );
  return linha;
}

function listaDeTipos(tipos) {
  const lista = elemento('ul', 'tipos');
  lista.append(...tipos.map(linhaDeTipo));
  return lista;
}

function linhaDeTipo(tipo) {
  const item = elemento('li');
  const contagem = elemento('span', 'tipo-contagem');
  contagem.append(
    elemento('strong', 'ok', String(tipo.sucessos)),
    elemento('span', null, ' sucesso, '),
    elemento('strong', tipo.falhas > 0 ? 'ruim' : null, String(tipo.falhas)),
    elemento('span', null, ' falha')
  );

  item.append(elemento('span', 'tipo-nome', tipo.tipo), contagem);

  // "3 falhas" nao ajuda ninguem; "3 falhas, todas pela mesma dependencia fora
  // do ar" aponta para onde olhar. Por isso o motivo aparece junto, e nao
  // escondido atras de um clique.
  const motivos = Object.entries(tipo.falhasPorMotivo);
  if (motivos.length > 0) {
    item.append(elemento('span', 'motivos',
      motivos.map(([motivo, quantas]) => motivo + ' x' + quantas).join('  ·  ')));
  }
  return item;
}

function ultimosEventos(eventos) {
  const bloco = elemento('details', 'ultimos');
  bloco.append(elemento('summary', null, 'Ultimos ' + eventos.length + ' eventos'));

  const lista = elemento('ul');
  lista.append(...eventos.map((evento) => {
    const item = elemento('li');
    item.append(
      elemento('span', 'evento-hora', hora(new Date(evento.ocorridoEm))),
      elemento('span', 'evento-tipo', evento.tipo),
      elemento('span', 'evento-resultado ' + evento.resultado.toLowerCase(),
        evento.resultado === 'SUCESSO' ? 'sucesso' : 'falha'),
      elemento('span', 'evento-motivo', evento.motivo || '')
    );
    return item;
  }));

  bloco.append(lista);
  return bloco;
}

function classeDaSituacao(situacao) {
  if (situacao === 'NO_AR') return 'no-ar';
  if (situacao === 'FORA_DO_AR') return 'fora-do-ar';
  return 'sem-dado';
}

/*
 * O texto entra por textContent, nunca por innerHTML. Nome de sistema, tipo e
 * motivo vem de configuracao e de sistemas externos; concatenar isso em HTML
 * seria abrir a porta para script injetado por quem publica evento.
 */
function elemento(tag, classe, texto) {
  const no = document.createElement(tag);
  if (classe) no.className = classe;
  if (texto !== undefined && texto !== null) no.textContent = texto;
  return no;
}

function hora(data) {
  return data.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
}

function numero(valor) {
  return valor.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function mostrarAviso(mensagem) {
  aviso.textContent = 'Nao foi possivel falar com a API (' + mensagem
    + '). Confira se ela esta rodando em ' + API
    + ' e se a origem desta pagina esta em sentinela.origens-permitidas.';
  aviso.classList.remove('oculto');
  atualizacao.textContent = 'sem conexao';

  // Tira o "Buscando sistemas...", que deixou de ser verdade. Os cartoes ja
  // desenhados ficam onde estao de proposito: diante de uma falha de rede, a
  // ultima leitura conhecida junto do aviso informa mais do que a tela em
  // branco -- desde que o cabecalho diga que a atualizacao parou, e ele diz.
  const esqueleto = painel.querySelector('.esqueleto');
  if (esqueleto) {
    esqueleto.remove();
  }
}

function esconderAviso() {
  aviso.classList.add('oculto');
}

painel.append(elemento('p', 'esqueleto', 'Buscando sistemas...'));
carregar();
setInterval(carregar, INTERVALO_DE_ATUALIZACAO_MS);

import json
import os
import unittest
from datetime import datetime, timezone
from unittest import mock

import exportar

HOJE = "2026-10-29"
AGORA = datetime(2026, 10, 29, 23, 59, tzinfo=timezone.utc)
PROJETOS = [{"id": "id-interno-xyz", "slug": "meu-projeto", "nome": "Meu Projeto"}]


def estado_com(**dias):
    # estado_com(d2026_10_28=(sucessos, total)) -> {"id-interno-xyz": {...}}
    registros = {}
    for chave, (sucessos, total) in dias.items():
        dia = chave[1:].replace("_", "-")
        registros[dia] = {"sucessos": sucessos, "total": total}
    return {"id-interno-xyz": registros}


def construir(estado):
    return exportar.construir_status(PROJETOS, estado, HOJE, AGORA)


class ListaBrancaTest(unittest.TestCase):
    def assertSoCamposPublicos(self, status):
        self.assertEqual(set(status), exportar.CAMPOS_PUBLICOS["raiz"])
        for projeto in status["projetos"]:
            self.assertEqual(set(projeto), exportar.CAMPOS_PUBLICOS["projeto"])
            for dia in projeto["dias"]:
                self.assertEqual(set(dia), exportar.CAMPOS_PUBLICOS["dia"])

    def test_com_dados_so_saem_os_campos_da_lista(self):
        self.assertSoCamposPublicos(construir(estado_com(d2026_10_28=(97, 100))))

    def test_sem_dados_o_formato_e_o_mesmo(self):
        self.assertSoCamposPublicos(construir({}))

    def test_nada_do_que_a_api_devolve_alem_do_resumo_chega_na_saida(self):
        resposta = {
            "sistemaId": "id-interno-xyz",
            "nome": "Nome Interno Secreto",
            "hoje": {
                "dia": "2026-10-28",
                "total": 100,
                "sucessos": 97,
                "falhas": 3,
                "tipos": [{"tipo": "camera.sabotagem", "falhasPorMotivo": {"imagem_bloqueada": 3}}],
            },
            "ultimos": [{"motivo": "imagem_bloqueada", "contexto": {"camera": "garagem-fundos"}}],
        }
        estado = {}
        exportar.registrar(estado, "id-interno-xyz", exportar.resumo_da_resposta(resposta))

        texto = json.dumps(construir(estado), ensure_ascii=False)

        for proibido in ("id-interno-xyz", "Nome Interno Secreto", "camera.sabotagem",
                         "imagem_bloqueada", "garagem-fundos"):
            self.assertNotIn(proibido, texto)


class DiaFechadoTest(unittest.TestCase):
    def test_o_dia_de_hoje_nunca_e_publicado(self):
        status = construir(estado_com(d2026_10_28=(97, 100), d2026_10_29=(10, 100)))
        dias = [d["dia"] for d in status["projetos"][0]["dias"]]
        self.assertEqual(dias, ["2026-10-28"])

    def test_dia_futuro_tambem_nao(self):
        status = construir(estado_com(d2026_10_30=(1, 1)))
        self.assertEqual(status["projetos"][0]["dias"], [])

    def test_janela_inclui_o_trigesimo_dia_e_exclui_o_anterior(self):
        status = construir(estado_com(d2026_09_29=(1, 1), d2026_09_28=(1, 1)))
        dias = [d["dia"] for d in status["projetos"][0]["dias"]]
        self.assertEqual(dias, ["2026-09-29"])


class NumeroHonestoTest(unittest.TestCase):
    def test_sucesso30d_e_ponderado_pelo_volume_e_nao_media_de_percentuais(self):
        # 100% em 100 verificacoes e 0% em 10: a media simples daria 50.
        status = construir(estado_com(d2026_10_27=(100, 100), d2026_10_28=(0, 10)))
        self.assertEqual(status["projetos"][0]["sucesso30d"], 90.9)

    def test_percentual_diario_tem_uma_casa(self):
        status = construir(estado_com(d2026_10_28=(253, 263)))
        self.assertEqual(status["projetos"][0]["dias"][0]["sucessoPct"], 96.2)

    def test_sem_nenhum_dia_fechado_o_numero_e_nulo_e_nao_100(self):
        self.assertIsNone(construir({})["projetos"][0]["sucesso30d"])

    def test_dia_sem_verificacao_e_lacuna_e_nao_vira_100(self):
        estado = {}
        registrou = exportar.registrar(estado, "id-interno-xyz", {"dia": "2026-10-28", "total": 0, "sucessos": 0})
        self.assertFalse(registrou)
        self.assertEqual(estado, {})


class RespostaDaApiTest(unittest.TestCase):
    def resposta(self, **hoje):
        base = {"dia": "2026-10-28", "total": 10, "sucessos": 9}
        base.update(hoje)
        return {"hoje": base}

    def test_resposta_valida(self):
        self.assertEqual(
            exportar.resumo_da_resposta(self.resposta()),
            {"dia": "2026-10-28", "total": 10, "sucessos": 9},
        )

    def test_recusa_o_que_nao_faz_sentido(self):
        invalidas = [
            self.resposta(sucessos=11),
            self.resposta(sucessos=-1),
            self.resposta(total="10"),
            self.resposta(total=True),
            self.resposta(dia="2026-13-45"),
            self.resposta(dia="ontem"),
            {"hoje": {}},
        ]
        for corpo in invalidas:
            with self.subTest(corpo=corpo):
                with self.assertRaises((ValueError, KeyError)):
                    exportar.resumo_da_resposta(corpo)


class ConfiguracaoTest(unittest.TestCase):
    def validar(self, projetos):
        vistos = set()
        for projeto in projetos:
            exportar.validar_projeto(projeto, vistos)

    def test_projeto_valido(self):
        self.validar(PROJETOS)

    def test_recusa_slug_fora_do_padrao_ou_repetido_e_nome_ruim(self):
        base = {"id": "a", "slug": "ok", "nome": "Nome"}
        invalidos = [
            [{**base, "slug": "Maiuscula"}],
            [{**base, "slug": "com espaco"}],
            [{**base, "nome": ""}],
            [{**base, "nome": "x" * 81}],
            [{**base, "nome": "quebra\nde linha"}],
            [{**base, "id": ""}],
            [base, base],
        ]
        for projetos in invalidos:
            with self.subTest(projetos=projetos):
                with self.assertRaises(ValueError):
                    self.validar(projetos)

    def test_endereco_da_api_exige_https_e_recusa_aspa(self):
        for endereco in ("", "http://x.com", "https://x.com/';alert(1);//", "https://x.com/caminho"):
            with self.subTest(endereco=endereco):
                with mock.patch.dict(os.environ, {"SENTINELA_URL_PRIVADA": endereco}):
                    with self.assertRaises(SystemExit):
                        exportar.endereco_da_api()


class PodaTest(unittest.TestCase):
    def test_poda_so_o_que_saiu_da_janela(self):
        estado = estado_com(d2026_09_28=(1, 1), d2026_09_29=(1, 1))
        exportar.podar(estado, HOJE)
        self.assertEqual(list(estado["id-interno-xyz"]), ["2026-09-29"])


if __name__ == "__main__":
    unittest.main()

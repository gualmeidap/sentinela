import http.client
import http.server
import json
import os
import pathlib
import tempfile
import threading
import unittest
from unittest import mock

import ver_painel_privado as vpp

CHAVE = "chave-de-leitura-de-teste-0123456789"
API = "https://api.exemplo.invalid"


class DestinoNoProxyTest(unittest.TestCase):
    def test_so_as_tres_formas_de_caminho_passam(self):
        permitidos = {
            "/api/sistemas": API + "/sistemas",
            "/api/sistemas/mantis/eventos": API + "/sistemas/mantis/eventos",
            "/api/sistemas/portal-ti/disponibilidade": API + "/sistemas/portal-ti/disponibilidade",
            "/api/sistemas?x=1": API + "/sistemas",
        }
        for caminho, esperado in permitidos.items():
            with self.subTest(caminho=caminho):
                self.assertEqual(vpp.destino_no_proxy(caminho, API), esperado)

    def test_o_resto_nao_atravessa(self):
        barrados = [
            "/api", "/api/", "/api/ping", "/api/eventos", "/api/sistemasX",
            "/api/sistemas/", "/api/sistemas//eventos", "/api/sistemas/a/b/c",
            "/api/sistemas/../eventos", "/api/sistemas/./eventos", "/api/sistemas/%2e%2e/eventos",
            "/api/sistemas/a/outra-coisa", "/api/sistemas/a b/eventos", "/api/sistemas/" + "a" * 65 + "/eventos",
            "/painel.js", "//evil.example/api/sistemas",
        ]
        for caminho in barrados:
            with self.subTest(caminho=caminho):
                self.assertIsNone(vpp.destino_no_proxy(caminho, API))


class ConfiguracaoTest(unittest.TestCase):
    def test_chave_ausente_ou_invalida_para_o_programa_sem_repetir_o_valor(self):
        for valor in ("", "curta", "com espaco no meio 1234567890", "linha\nnova-1234567890123456"):
            with self.subTest(tamanho=len(valor)):
                with mock.patch.dict(os.environ, {"SENTINELA_CHAVE_LEITURA": valor}):
                    with self.assertRaises(SystemExit) as contexto:
                        vpp.chave_de_leitura()
                if valor.strip():
                    self.assertNotIn(valor.strip(), str(contexto.exception.code))

    def test_chave_valida(self):
        with mock.patch.dict(os.environ, {"SENTINELA_CHAVE_LEITURA": CHAVE + "\n"}):
            self.assertEqual(vpp.chave_de_leitura(), CHAVE)

    def test_endereco_exige_https_e_recusa_aspa(self):
        for endereco in ("", "http://x.com", "https://x.com/';alert(1);//", "https://x.com/caminho"):
            with self.subTest(endereco=endereco):
                with mock.patch.dict(os.environ, {"SENTINELA_URL_PRIVADA": endereco}):
                    with self.assertRaises(SystemExit):
                        vpp.endereco_da_api()

    def test_a_pagina_passa_a_apontar_para_o_proxy_e_o_endereco_real_nao_entra_nela(self):
        with tempfile.TemporaryDirectory() as pasta:
            destino = pathlib.Path(pasta)
            vpp.preparar(destino)
            painel = (destino / "painel.js").read_text(encoding="utf-8")
        self.assertIn("const API = '/api';", painel)
        self.assertNotIn("localhost:8080", painel)


class ProxyAoVivoTest(unittest.TestCase):
    """Um proxy de verdade em 127.0.0.1, com uma API de mentira por tras."""

    @classmethod
    def setUpClass(cls):
        cls.visto = []
        visto = cls.visto

        class ApiDeMentira(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                visto.append((self.path, self.headers.get("X-Chave-Leitura")))
                corpo = json.dumps({"ok": True}).encode()
                self.send_response(401 if self.path.endswith("/recusa") else 200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(corpo)))
                self.end_headers()
                self.wfile.write(corpo)

            def log_message(self, *argumentos):
                pass

        cls.api = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ApiDeMentira)
        endereco = f"http://127.0.0.1:{cls.api.server_address[1]}"
        manipulador = lambda *a, **k: vpp.Manipulador(*a, endereco=endereco, chave=CHAVE, **k)  # noqa: E731
        cls.proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), manipulador)
        cls.porta = cls.proxy.server_address[1]
        for servidor in (cls.api, cls.proxy):
            threading.Thread(target=servidor.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        for servidor in (cls.api, cls.proxy):
            servidor.shutdown()
            servidor.server_close()

    def setUp(self):
        self.visto.clear()

    def pedir(self, caminho, host=None):
        conexao = http.client.HTTPConnection("127.0.0.1", self.porta, timeout=5)
        conexao.putrequest("GET", caminho, skip_host=True)
        conexao.putheader("Host", host or f"localhost:{self.porta}")
        conexao.endheaders()
        resposta = conexao.getresponse()
        corpo = resposta.read()
        conexao.close()
        return resposta, corpo

    def test_repassa_com_a_chave_e_devolve_o_corpo(self):
        resposta, corpo = self.pedir("/api/sistemas/mantis/eventos")

        self.assertEqual(resposta.status, 200)
        self.assertEqual(json.loads(corpo), {"ok": True})
        self.assertEqual(self.visto, [("/sistemas/mantis/eventos", CHAVE)])

    def test_a_chave_nunca_volta_para_o_navegador(self):
        resposta, corpo = self.pedir("/api/sistemas")

        self.assertNotIn(CHAVE.encode(), corpo)
        self.assertNotIn(CHAVE, str(resposta.getheaders()))

    def test_status_de_recusa_da_api_chega_igual_a_pagina(self):
        # A pagina mostra o aviso com o status que a API deu; o proxy nao o troca.
        resposta, _ = self.pedir("/api/sistemas/recusa")

        self.assertEqual(resposta.status, 401)

    def test_host_estranho_e_barrado_sem_tocar_na_api(self):
        resposta, _ = self.pedir("/api/sistemas", host="atacante.example:80")

        self.assertEqual(resposta.status, 403)
        self.assertEqual(self.visto, [])

    def test_caminho_fora_da_lista_nao_chega_na_api(self):
        resposta, _ = self.pedir("/api/ping")

        self.assertEqual(resposta.status, 404)
        self.assertEqual(self.visto, [])

    def test_fora_do_prefixo_quem_responde_e_a_pasta_estatica_e_nao_a_api(self):
        resposta, _ = self.pedir("/nao-existe.html")
        self.assertEqual(resposta.status, 404)
        self.assertEqual(self.visto, [])


if __name__ == "__main__":
    unittest.main()

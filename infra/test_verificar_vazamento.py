import tempfile
import unittest
from pathlib import Path

import verificar_vazamento as vv

# Os valores de teste sao montados em tempo de execucao, e nao escritos por
# inteiro: se estivessem inteiros neste arquivo, a propria verificacao, rodando
# sobre o repositorio, acharia o vazamento de mentira.
IP_INTERNO = ".".join(("10", "1", "2", "3"))
IP_DO_TAILSCALE = ".".join(("100", "101", "102", "103"))
IP_DE_DOCUMENTACAO = ".".join(("192", "0", "2", "10"))
CHAVE_AWS = "AKIA" + "B" * 16
TOKEN_GITHUB = "ghp_" + "c" * 36
LAMBDA_DESCONHECIDA = "abc123def456" + ".lambda-url.us-east-1.on.aws"
LAMBDA_PUBLICA = sorted(vv.URLS_PUBLICAS)[0] + ".lambda-url.us-east-1.on.aws"
LAMBDA_DE_EXEMPLO = "xxxx" + ".lambda-url.us-east-1.on.aws"
CONTA_DA_NOSSA = "123456789012"


def categorias(linha, termos=()):
    return vv.categorias_da_linha(linha, termos)


class FormasTest(unittest.TestCase):
    def test_ip_de_rede_interna_e_do_tailscale_sao_achados(self):
        for ip in (IP_INTERNO, IP_DO_TAILSCALE, ".".join(("192", "168", "0", "9")), ".".join(("172", "20", "0", "1"))):
            with self.subTest(ip=ip):
                self.assertIn("ip_de_rede_interna", categorias(f"conecta em {ip}:389"))

    def test_endereco_local_e_de_documentacao_passam(self):
        for ip in ("127.0.0.1", "0.0.0.0", IP_DE_DOCUMENTACAO, ".".join(("172", "32", "0", "1")), ".".join(("100", "128", "0", "1"))):
            with self.subTest(ip=ip):
                self.assertEqual(categorias(f"conecta em {ip}"), [])

    def test_numero_de_versao_nao_e_confundido_com_ip(self):
        self.assertEqual(categorias("<version>3.5.6.1</version> e 110.1.2.3"), [])

    def test_nome_de_maquina_do_tailscale(self):
        self.assertIn("nome_de_maquina_tailscale", categorias("host: camera." + "tail1234" + ".ts" + ".net"))

    def test_credenciais(self):
        self.assertIn("chave_de_acesso_aws", categorias(f"id = {CHAVE_AWS}"))
        self.assertIn("segredo_aws", categorias("aws_secret_access_key = " + "d" * 40))
        self.assertIn("token_do_github", categorias(f"token: {TOKEN_GITHUB}"))
        self.assertIn("chave_privada", categorias("-----BEGIN " + "RSA PRIVATE KEY-----"))

    def test_texto_comum_com_a_palavra_chave_passa(self):
        self.assertEqual(categorias("a chave de aplicacao vem de uma variavel de ambiente"), [])


class UrlDeLambdaTest(unittest.TestCase):
    def test_id_desconhecido_e_achado(self):
        self.assertIn("url_de_lambda_fora_da_lista_publica", categorias(f"https://{LAMBDA_DESCONHECIDA}/"))

    def test_instancia_publica_e_exemplo_passam(self):
        self.assertEqual(categorias(f"[painel](https://{LAMBDA_PUBLICA}/)"), [])
        self.assertEqual(categorias(f"SENTINELA_URL=https://{LAMBDA_DE_EXEMPLO}"), [])

    def test_um_id_publico_e_um_desconhecido_na_mesma_linha_ainda_acusa(self):
        linha = f"{LAMBDA_PUBLICA} e {LAMBDA_DESCONHECIDA}"
        self.assertIn("url_de_lambda_fora_da_lista_publica", categorias(linha))


class ContaAwsTest(unittest.TestCase):
    def test_conta_em_arn_e_em_nome_de_bucket(self):
        self.assertIn("id_de_conta_aws", categorias(f"arn:aws:iam::{CONTA_DA_NOSSA}:role/x"))
        self.assertIn("id_de_conta_aws", categorias(f"bucket: sentinela-codigo-{CONTA_DA_NOSSA}"))

    def test_conta_publica_do_adaptador_passa(self):
        conta = next(iter(vv.CONTAS_AWS_PUBLICAS))
        self.assertEqual(categorias(f"arn:aws:lambda:us-east-1:{conta}:layer:Adapter:28"), [])

    def test_numero_longo_solto_nao_e_conta(self):
        self.assertEqual(categorias("timestamp 1730000000000 e id 123456789012 solto"), [])

    def test_arn_com_variavel_do_cloudformation_passa(self):
        self.assertEqual(categorias("arn:aws:s3:::${Bucket}/*  e  ${AWS::AccountId}"), [])


class ArquivoProibidoTest(unittest.TestCase):
    def test_arquivos_so_da_maquina_de_quem_opera(self):
        for caminho in ("CLAUDE.md", "CLAUDE.local.md", "api/src/main/resources/application-privado.yml",
                        "publicador-auditoria/config.json", "publicador-auditoria/cursor.json",
                        "exportador-publico/config.json", "exportador-publico/estado.json",
                        "exportador-publico/status.json", ".env", "x/.env.producao", "alvos.properties",
                        "infra/termos-proibidos.local.txt", "chave.pem"):
            with self.subTest(caminho=caminho):
                self.assertTrue(vv.arquivo_proibido(caminho))

    def test_os_exemplos_versionados_passam(self):
        for caminho in ("README.md", "publicador-auditoria/config.exemplo.json",
                        "exportador-publico/config.exemplo.json", "alvos.exemplo.properties",
                        "api/src/main/resources/application.yml", "infra/api.yaml"):
            with self.subTest(caminho=caminho):
                self.assertFalse(vv.arquivo_proibido(caminho))

    def test_arquivo_proibido_vira_achado_mesmo_sem_conteudo(self):
        achados = vv.achados_do_arquivo("CLAUDE.md", "")
        self.assertEqual([a[0] for a in achados], ["arquivo_que_nao_deve_ser_versionado"])


class TermosLocaisTest(unittest.TestCase):
    def test_termo_local_acusa_sem_diferenciar_caixa(self):
        self.assertIn("termo_da_lista_local", categorias("fala do Sistema Secreto aqui", ("sistema secreto",)))

    def test_sem_lista_local_nada_acusa(self):
        self.assertEqual(categorias("fala do Sistema Secreto aqui"), [])

    def test_leitura_ignora_comentario_e_linha_vazia(self):
        with tempfile.TemporaryDirectory() as pasta:
            caminho = Path(pasta) / "termos.txt"
            caminho.write_text("# comentario\n\n  Empresa Exemplo  \n", encoding="utf-8")
            self.assertEqual(vv.ler_termos_locais(caminho), ("empresa exemplo",))

    def test_arquivo_ausente_e_lista_vazia(self):
        self.assertEqual(vv.ler_termos_locais(Path("nao-existe-mesmo.txt")), ())


class RelatorioTest(unittest.TestCase):
    def test_o_relatorio_nunca_repete_o_valor_encontrado(self):
        texto = f"a\nb {IP_INTERNO}\nc {CHAVE_AWS}\nd {LAMBDA_DESCONHECIDA}\ne segredo-local"
        achados = vv.achados_do_arquivo("doc.md", texto, ("segredo-local",))
        impresso = "\n".join(vv.relatorio(achados))

        self.assertEqual(len(achados), 4)
        for valor in (IP_INTERNO, CHAVE_AWS, "abc123def456", "segredo-local"):
            self.assertNotIn(valor, impresso)
        self.assertIn("doc.md:2", impresso)

    def test_arquivo_limpo_nao_gera_achado(self):
        self.assertEqual(vv.achados_do_arquivo("doc.md", "texto\nsem nada\n"), [])


class ArquivoBinarioTest(unittest.TestCase):
    def test_binario_e_reconhecido_pelo_byte_nulo(self):
        self.assertFalse(vv.e_texto(b"PK\x03\x04\x00\x00"))
        self.assertTrue(vv.e_texto("texto comum com acentuação".encode("utf-8")))


if __name__ == "__main__":
    unittest.main()

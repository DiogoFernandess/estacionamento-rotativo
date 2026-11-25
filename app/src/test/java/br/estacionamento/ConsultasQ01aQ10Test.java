package br.estacionamento;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 — um teste por consulta de {@code postgresql/03_consultas.sql}.
 * O arquivo é lido em disco e fatiado pelos marcadores {@code -- @Qnn}; cada
 * teste executa exatamente um pedaço e confere linhas/valores esperados (B2).
 * Tudo roda em transação com rollback, sem alterar o banco semeado.
 */
class ConsultasQ01aQ10Test {

    private static final Pattern MARCADOR = Pattern.compile("^-- @Q\\d{2}$");

    private Connection conexao;

    @BeforeEach
    void abrirConexao() throws SQLException {
        String url = System.getenv().getOrDefault(
                "DB_URL", "jdbc:postgresql://localhost:5432/estacionamento");
        String usuario = System.getenv().getOrDefault("DB_USER", "estacionamento");
        String senha = System.getenv().getOrDefault("DB_PASSWORD", "estacionamento_pwd");
        conexao = DriverManager.getConnection(url, usuario, senha);
        conexao.setAutoCommit(false);
    }

    @AfterEach
    void reverter() throws SQLException {
        if (conexao != null) {
            try {
                conexao.rollback();
            } finally {
                conexao.close();
            }
        }
    }

    @Test
    void q01OcupacaoAtualPorTipo() throws Exception {
        comConsulta(1, rs -> {
            assertTrue(rs.next(), "linha de CARRO");
            assertEquals("CARRO", rs.getString("tipo"));
            assertEquals(100, rs.getInt("total"));
            assertEquals(10, rs.getInt("ocupadas"));
            assertEquals(90, rs.getInt("livres"));
            assertDecimal("10.00", rs.getBigDecimal("percentual_ocupacao"), "percentual CARRO");

            assertTrue(rs.next(), "linha de MOTO");
            assertEquals("MOTO", rs.getString("tipo"));
            assertEquals(20, rs.getInt("total"));
            assertEquals(4, rs.getInt("ocupadas"));
            assertEquals(16, rs.getInt("livres"));
            assertDecimal("20.00", rs.getBigDecimal("percentual_ocupacao"), "percentual MOTO");

            assertFalse(rs.next(), "Q01 deve devolver só 2 linhas");
        });
    }

    @Test
    void q02ReceitaDiariaComAcumulado() throws Exception {
        String[] receitas = {"25.00", "36.00", "47.00", "20.00", "104.00"};
        String[] acumulados = {"25.00", "61.00", "108.00", "128.00", "232.00"};

        comConsulta(2, rs -> {
            Date anterior = null;
            for (int i = 0; i < receitas.length; i++) {
                assertTrue(rs.next(), "linha " + (i + 1) + " de Q02");
                Date dia = rs.getDate("dia");
                assertNotNull(dia);
                if (anterior != null) {
                    assertTrue(dia.after(anterior), "dias devem estar em ordem crescente");
                }
                anterior = dia;
                assertDecimal(receitas[i], rs.getBigDecimal("receita"), "receita do dia " + dia);
                assertDecimal(acumulados[i], rs.getBigDecimal("acumulado"), "acumulado do dia " + dia);
            }
            assertFalse(rs.next(), "Q02 deve devolver só 5 dias com pagamento");
        });
    }

    @Test
    void q03Top10ComEmpate() throws Exception {
        int[] posicoes = {1, 2, 3, 4, 5, 6, 7, 7, 9, 10, 10};
        String[] placas = {"ABC9E01", "ABC8D90", "ABC7D89", "MOT8H90", "MOT7G89", "ABC6D78",
                "ABC4D56", "ABC5D67", "MOT6F78", "ABC2D34", "ABC3D45"};
        String[] gastos = {"60.00", "45.00", "44.00", "40.00", "30.00", "20.00",
                "16.00", "16.00", "14.00", "12.00", "12.00"};

        comConsulta(3, rs -> {
            for (int i = 0; i < placas.length; i++) {
                assertTrue(rs.next(), "linha " + (i + 1) + " de Q03");
                assertEquals(posicoes[i], rs.getInt("posicao"));
                assertEquals(placas[i], rs.getString("placa"));
                assertDecimal(gastos[i], rs.getBigDecimal("gasto"), "gasto de " + placas[i]);
            }
            assertFalse(rs.next(), "RANK() <= 10 inclui o empate: 11 linhas");
        });
    }

    @Test
    void q04TempoMedioMinimoEMaximoPorTipo() throws Exception {
        comConsulta(4, rs -> {
            assertTrue(rs.next(), "linha de CARRO");
            assertEquals("CARRO", rs.getString("tipo"));
            assertDecimal("108.34", rs.getBigDecimal("media_min"), "média CARRO");
            assertDecimal("15.00", rs.getBigDecimal("minimo_min"), "mínimo CARRO");
            assertDecimal("300.02", rs.getBigDecimal("maximo_min"), "máximo CARRO (5h00:01)");

            assertTrue(rs.next(), "linha de MOTO");
            assertEquals("MOTO", rs.getString("tipo"));
            assertDecimal("84.38", rs.getBigDecimal("media_min"), "média MOTO");
            assertDecimal("15.00", rs.getBigDecimal("minimo_min"), "mínimo MOTO");
            assertDecimal("300.00", rs.getBigDecimal("maximo_min"), "máximo MOTO (5h00:00)");

            assertFalse(rs.next(), "Q04 deve devolver só 2 linhas");
        });
    }

    @Test
    void q05MovimentoPorHora() throws Exception {
        comConsulta(5, rs -> {
            int entradas = 0;
            int saidas = 0;
            int horas = 0;
            Integer horaAnterior = null;
            while (rs.next()) {
                int hora = rs.getInt("hora");
                assertTrue(hora >= 0 && hora <= 23, "hora fora de 0..23: " + hora);
                if (horaAnterior != null) {
                    assertTrue(hora > horaAnterior, "horas devem estar em ordem crescente");
                }
                horaAnterior = hora;
                int e = rs.getInt("entradas");
                int s = rs.getInt("saidas");
                assertTrue(e + s > 0, "linha sem movimento na hora " + hora);
                entradas += e;
                saidas += s;
                horas++;
            }
            assertTrue(horas > 0, "Q05 não devolveu linhas");
            assertEquals(31, entradas, "todas as 31 permanências têm entrada");
            assertEquals(17, saidas, "somente as 17 permanências fechadas têm saída");
        });
    }

    @Test
    void q06ReceitaPorFormaDePagamento() throws Exception {
        comConsulta(6, rs -> {
            assertTrue(rs.next());
            assertEquals("CARTAO", rs.getString("forma"));
            assertDecimal("101.00", rs.getBigDecimal("receita"), "receita CARTAO");
            assertDecimal("29.11", rs.getBigDecimal("percentual"), "percentual CARTAO");

            assertTrue(rs.next());
            assertEquals("DINHEIRO", rs.getString("forma"));
            assertDecimal("110.00", rs.getBigDecimal("receita"), "receita DINHEIRO");
            assertDecimal("31.70", rs.getBigDecimal("percentual"), "percentual DINHEIRO");

            assertTrue(rs.next());
            assertEquals("PIX", rs.getString("forma"));
            assertDecimal("136.00", rs.getBigDecimal("receita"), "receita PIX");
            assertDecimal("39.19", rs.getBigDecimal("percentual"), "percentual PIX");

            assertFalse(rs.next(), "Q06 deve devolver só 3 formas com pagamento");
        });
    }

    @Test
    void q07VagasNuncaUtilizadas() throws Exception {
        comConsulta(7, rs -> {
            int linhas = 0;
            boolean c100 = false;
            boolean m020 = false;
            while (rs.next()) {
                String codigo = rs.getString("codigo");
                String tipo = rs.getString("tipo");
                assertEquals(codigo.startsWith("C") ? "CARRO" : "MOTO", tipo, "tipo de " + codigo);
                assertFalse(codigo.equals("C001") || codigo.equals("M001"),
                        "vaga usada não deve aparecer: " + codigo);
                c100 |= codigo.equals("C100");
                m020 |= codigo.equals("M020");
                linhas++;
            }
            assertEquals(89, linhas, "120 ativas - 31 usadas");
            assertTrue(c100, "fronteira C100 deve aparecer");
            assertTrue(m020, "fronteira M020 deve aparecer");
        });
    }

    @Test
    void q08ToleranciaTicketPerdidoECobrado() throws Exception {
        comConsulta(8, rs -> {
            assertTrue(rs.next());
            assertEquals("COBRADO", rs.getString("categoria"));
            assertEquals(13, rs.getInt("quantidade"));
            assertDecimal("247.00", rs.getBigDecimal("receita"), "receita COBRADO");

            assertTrue(rs.next());
            assertEquals("TICKET_PERDIDO", rs.getString("categoria"));
            assertEquals(2, rs.getInt("quantidade"));
            assertDecimal("100.00", rs.getBigDecimal("receita"), "receita TICKET_PERDIDO");

            assertTrue(rs.next());
            assertEquals("TOLERANCIA", rs.getString("categoria"));
            assertEquals(2, rs.getInt("quantidade"));
            assertDecimal("0.00", rs.getBigDecimal("receita"), "receita TOLERANCIA");

            assertFalse(rs.next(), "13 + 2 + 2 = 17 permanências fechadas");
        });
    }

    @Test
    void q09ReceitaMensalComVariacao() throws Exception {
        LocalDate mesAtual = mesAtual();
        comConsulta(9, rs -> {
            assertTrue(rs.next());
            assertEquals(mesAtual.minusMonths(1), rs.getDate("mes").toLocalDate());
            assertDecimal("115.00", rs.getBigDecimal("receita"), "receita do mês anterior");
            assertNull(rs.getBigDecimal("receita_anterior"), "primeiro mês não tem anterior");
            assertNull(rs.getBigDecimal("variacao_percentual"), "primeiro mês não tem variação");

            assertTrue(rs.next());
            assertEquals(mesAtual, rs.getDate("mes").toLocalDate());
            assertDecimal("232.00", rs.getBigDecimal("receita"), "receita do mês da subida");
            assertDecimal("115.00", rs.getBigDecimal("receita_anterior"), "mês anterior");
            assertDecimal("101.74", rs.getBigDecimal("variacao_percentual"), "variação percentual");

            assertFalse(rs.next(), "Q09 deve devolver só 2 meses com pagamento");
        });
    }

    @Test
    void q10ReceitaPorTipoEFormaComSubtotais() throws Exception {
        Object[][] esperado = {
                {"CARRO", "CARTAO", "60.00"},
                {"CARRO", "DINHEIRO", "88.00"},
                {"CARRO", "PIX", "77.00"},
                {"CARRO", null, "225.00"},
                {"MOTO", "CARTAO", "41.00"},
                {"MOTO", "DINHEIRO", "22.00"},
                {"MOTO", "PIX", "59.00"},
                {"MOTO", null, "122.00"},
                {null, null, "347.00"},
        };

        comConsulta(10, rs -> {
            for (int i = 0; i < esperado.length; i++) {
                assertTrue(rs.next(), "linha " + (i + 1) + " de Q10");
                assertEquals(esperado[i][0], rs.getString("tipo"), "tipo na linha " + (i + 1));
                assertEquals(esperado[i][1], rs.getString("forma"), "forma na linha " + (i + 1));
                assertDecimal((String) esperado[i][2], rs.getBigDecimal("receita"),
                        "receita na linha " + (i + 1));
            }
            assertFalse(rs.next(), "6 células + 2 subtotais + total geral = 9 linhas");
        });
    }

    /** Executa só o pedaço do arquivo delimitado por {@code -- @Qnn}. */
    private void comConsulta(int numero, Verificacao verificacao) throws Exception {
        String sql = consulta(numero);
        assertTrue(sql.trim().endsWith(";"), "@Q" + numero + " deve terminar com ';'");
        try (PreparedStatement ps = conexao.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            verificacao.aplicar(rs);
        }
    }

    private String consulta(int numero) throws IOException {
        String marcador = String.format("-- @Q%02d", numero);
        List<String> linhas = Files.readAllLines(caminhoConsultas(), StandardCharsets.UTF_8);
        StringBuilder sql = new StringBuilder();
        boolean encontrado = false;
        for (String linha : linhas) {
            if (MARCADOR.matcher(linha.trim()).matches()) {
                if (encontrado) {
                    break;
                }
                if (linha.trim().equals(marcador)) {
                    encontrado = true;
                }
                continue;
            }
            if (encontrado) {
                sql.append(linha).append('\n');
            }
        }
        assertTrue(encontrado, "marcador " + marcador + " não encontrado");
        assertTrue(sql.toString().contains("SELECT"), "@Q" + numero + " sem SELECT");
        return sql.toString();
    }

    private Path caminhoConsultas() {
        String configurado = System.getenv("SQL_CONSULTAS");
        if (configurado != null && !configurado.isBlank()) {
            return Path.of(configurado);
        }
        for (String candidato : List.of("/scripts/postgresql/03_consultas.sql",
                "postgresql/03_consultas.sql", "../postgresql/03_consultas.sql")) {
            Path caminho = Path.of(candidato);
            if (Files.isRegularFile(caminho)) {
                return caminho;
            }
        }
        throw new IllegalStateException("postgresql/03_consultas.sql não encontrado");
    }

    private LocalDate mesAtual() throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(
                "SELECT DATE_TRUNC('month', CURRENT_DATE)::date")) {
            ResultSet rs = ps.executeQuery();
            assertTrue(rs.next(), "CURRENT_DATE sem linha");
            return rs.getDate(1).toLocalDate();
        }
    }

    private static void assertDecimal(String esperado, BigDecimal atual, String mensagem) {
        assertNotNull(atual, mensagem + ": valor nulo");
        assertEquals(0, new BigDecimal(esperado).compareTo(atual), mensagem);
    }

    @FunctionalInterface
    private interface Verificacao {
        void aplicar(ResultSet rs) throws SQLException;
    }
}

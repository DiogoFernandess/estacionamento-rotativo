package br.estacionamento;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4 — 7 testes de procedures (registrar_entrada / registrar_saida).
 * Cada teste roda em transação com rollback; após erro esperado, usa savepoint
 * para manter a transação utilizável antes do rollback final.
 */
class ProcedureTest {

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
    void registrarEntradaFeliz_normalizaECriaPermanencia() throws SQLException {
        try (CallableStatement cs = conexao.prepareCall("CALL registrar_entrada(?, ?)")) {
            cs.setString(1, "abc-1z23");
            cs.setString(2, "carro");
            cs.execute();
        }

        try (PreparedStatement ps = conexao.prepareStatement("""
                SELECT v.placa, tv.nome AS tipo, va.codigo AS vaga,
                       p.status, p.valor, p.ticket_perdido
                FROM permanencia p
                JOIN veiculo v ON v.veiculo_id = p.veiculo_id
                JOIN tipo_veiculo tv ON tv.tipo_id = v.tipo_id
                JOIN vaga va ON va.vaga_id = p.vaga_id
                WHERE v.placa = 'ABC1Z23' AND p.status = 'ABERTA'
                """);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "permanência aberta não encontrada");
            assertEquals("ABC1Z23", rs.getString("placa"));
            assertEquals("CARRO", rs.getString("tipo"));
            assertEquals("C001", rs.getString("vaga"));
            assertEquals("ABERTA", rs.getString("status"));
            assertDecimal("0.00", rs.getBigDecimal("valor"), "valor inicial");
            assertFalse(rs.getBoolean("ticket_perdido"));
            assertFalse(rs.next(), "deveria haver apenas uma permanência aberta");
        }
    }

    @Test
    void registrarEntradaSemVaga_retornaEstac001() throws SQLException {
        ocuparVagasLivresMoto();
        Savepoint sp = conexao.setSavepoint("antes_sem_vaga");

        SQLException erro = assertThrows(SQLException.class, () -> {
            try (CallableStatement cs = conexao.prepareCall("CALL registrar_entrada(?, ?)")) {
                cs.setString(1, "NOV1M01");
                cs.setString(2, "MOTO");
                cs.execute();
            }
        });

        assertEstac("ESTAC-001", erro);
        conexao.rollback(sp);
    }

    @Test
    void registrarEntradaTipoIncompativel_retornaEstac004() throws SQLException {
        Savepoint sp = conexao.setSavepoint("antes_tipo_incompativel");

        SQLException erro = assertThrows(SQLException.class, () -> {
            try (CallableStatement cs = conexao.prepareCall("CALL registrar_entrada(?, ?)")) {
                cs.setString(1, "XYZ1K11"); // moto no seed
                cs.setString(2, "CARRO");
                cs.execute();
            }
        });

        assertEstac("ESTAC-004", erro);
        conexao.rollback(sp);
    }

    @Test
    void registrarEntradaVeiculoJaAberto_retornaEstac006() throws SQLException {
        Savepoint sp = conexao.setSavepoint("antes_ja_aberto");

        SQLException erro = assertThrows(SQLException.class, () -> {
            try (CallableStatement cs = conexao.prepareCall("CALL registrar_entrada(?, ?)")) {
                cs.setString(1, "QRS1A11"); // já tem permanência aberta no seed
                cs.setString(2, "CARRO");
                cs.execute();
            }
        });

        assertEstac("ESTAC-006", erro);
        conexao.rollback(sp);
    }

    @Test
    void registrarSaidaTolerancia_valorZeroSemPagamento() throws SQLException {
        int permanenciaId = inserirPermanenciaAbertaCarro("C001", "15 minutes");

        try (CallableStatement cs = conexao.prepareCall("CALL registrar_saida(?, ?, ?)")) {
            cs.setInt(1, permanenciaId);
            cs.setString(2, null);
            cs.setBoolean(3, false);
            cs.execute();
        }

        try (PreparedStatement ps = conexao.prepareStatement("""
                SELECT status, valor, ticket_perdido,
                       (SELECT COUNT(*) FROM pagamento pg WHERE pg.permanencia_id = p.permanencia_id) AS pagamentos
                FROM permanencia p WHERE permanencia_id = ?
                """)) {
            ps.setInt(1, permanenciaId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("FECHADA", rs.getString("status"));
                assertDecimal("0.00", rs.getBigDecimal("valor"), "tolerância");
                assertFalse(rs.getBoolean("ticket_perdido"));
                assertEquals(0, rs.getInt("pagamentos"));
            }
        }
    }

    @Test
    void registrarSaidaTeto_calculaEInserePagamento() throws SQLException {
        int permanencia10h = buscarId(
                "SELECT permanencia_id FROM permanencia WHERE status = 'ABERTA' ORDER BY entrada LIMIT 1");

        try (CallableStatement cs = conexao.prepareCall("CALL registrar_saida(?, ?, ?)")) {
            cs.setInt(1, permanencia10h);
            cs.setString(2, "PIX");
            cs.setBoolean(3, false);
            cs.execute();
        }

        try (PreparedStatement ps = conexao.prepareStatement("""
                SELECT p.status, p.valor, pg.forma, pg.valor AS pagamento_valor
                FROM permanencia p
                JOIN pagamento pg ON pg.permanencia_id = p.permanencia_id
                WHERE p.permanencia_id = ?
                """)) {
            ps.setInt(1, permanencia10h);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("FECHADA", rs.getString("status"));
                assertDecimal("45.00", rs.getBigDecimal("valor"), "teto carro 10h");
                assertEquals("PIX", rs.getString("forma"));
                assertDecimal("45.00", rs.getBigDecimal("pagamento_valor"), "pagamento teto");
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void registrarSaidaDupla_retornaEstac002() throws SQLException {
        int permanenciaId = inserirPermanenciaAbertaCarro("C001", "30 minutes");

        try (CallableStatement cs = conexao.prepareCall("CALL registrar_saida(?, ?, ?)")) {
            cs.setInt(1, permanenciaId);
            cs.setString(2, "PIX");
            cs.setBoolean(3, false);
            cs.execute();
        }

        Savepoint sp = conexao.setSavepoint("antes_saida_dupla");
        SQLException erro = assertThrows(SQLException.class, () -> {
            try (CallableStatement cs = conexao.prepareCall("CALL registrar_saida(?, ?, ?)")) {
                cs.setInt(1, permanenciaId);
                cs.setString(2, "PIX");
                cs.setBoolean(3, false);
                cs.execute();
            }
        });

        assertEstac("ESTAC-002", erro);
        conexao.rollback(sp);
    }

    private void ocuparVagasLivresMoto() throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement("""
                INSERT INTO veiculo (placa, tipo_id)
                SELECT 'SEM1A' || lpad(g::text, 2, '0'), 2
                FROM generate_series(1, 16) g
                """)) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conexao.prepareStatement("""
                INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id, entrada, valor, status, ticket_perdido)
                SELECT v.veiculo_id, va.vaga_id, 2, CURRENT_TIMESTAMP, 0, 'ABERTA', FALSE
                FROM (
                    SELECT veiculo_id, row_number() OVER (ORDER BY veiculo_id) rn
                    FROM veiculo WHERE placa LIKE 'SEM1A%'
                ) v
                JOIN (
                    SELECT va.vaga_id, row_number() OVER (ORDER BY va.codigo) rn
                    FROM vaga va
                    WHERE va.tipo_id = 2 AND va.ativa
                      AND NOT EXISTS (
                          SELECT 1 FROM permanencia p
                          WHERE p.vaga_id = va.vaga_id AND p.status = 'ABERTA'
                      )
                ) va ON va.rn = v.rn
                """)) {
            ps.executeUpdate();
        }
    }

    private int inserirPermanenciaAbertaCarro(String codigoVaga, String intervalo) throws SQLException {
        int vagaId = buscarId("SELECT vaga_id FROM vaga WHERE codigo = '" + codigoVaga + "'");
        int veiculoId;
        try (PreparedStatement ps = conexao.prepareStatement(
                "INSERT INTO veiculo (placa, tipo_id) VALUES (?, 1) RETURNING veiculo_id")) {
            String placa = "TST" + Math.abs(codigoVaga.hashCode() % 10) + "A"
                    + String.format("%02d", Math.abs(intervalo.hashCode() % 100));
            // garante placa válida e única na transação
            placa = placaValidaUnica();
            ps.setString(1, placa);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                veiculoId = rs.getInt(1);
            }
        }
        try (PreparedStatement ps = conexao.prepareStatement(
                "INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id, entrada, valor, status, ticket_perdido)"
                        + " VALUES (?, ?, 1, CURRENT_TIMESTAMP - (?::interval), 0, 'ABERTA', FALSE)"
                        + " RETURNING permanencia_id")) {
            ps.setInt(1, veiculoId);
            ps.setInt(2, vagaId);
            ps.setString(3, intervalo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private int seqPlaca = 0;

    private String placaValidaUnica() {
        seqPlaca++;
        return String.format("TST1Z%02d", seqPlaca % 100);
    }

    private int buscarId(String sql) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "dado não encontrado: " + sql);
            return rs.getInt(1);
        }
    }

    private static void assertEstac(String codigo, SQLException erro) {
        String msg = erro.getMessage() == null ? "" : erro.getMessage();
        assertTrue(msg.contains(codigo),
                "esperava " + codigo + " em: " + msg + " (SQLState=" + erro.getSQLState() + ")");
        assertEquals("P0001", erro.getSQLState(), "SQLState de " + codigo);
    }

    private static void assertDecimal(String esperado, BigDecimal atual, String mensagem) {
        assertEquals(0, new BigDecimal(esperado).compareTo(atual),
                mensagem + ": esperado=" + esperado + " atual=" + atual);
    }
}

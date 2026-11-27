package br.estacionamento;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4 — 3 testes de triggers (trg_permanencia_valida / trg_permanencia_auditoria).
 * Cada teste roda em transação com rollback; após erro esperado, usa savepoint.
 */
class TriggerTest {

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
    void triggerValida_bloqueiaTipoIncompativel_estac004() throws SQLException {
        int veiculoMoto = buscarId("SELECT veiculo_id FROM veiculo WHERE placa = 'XYZ1K11'"); // MOTO
        int vagaCarro = buscarId("SELECT vaga_id FROM vaga WHERE codigo = 'C001'"); // CARRO, livre
        int tarifaMoto = buscarId("SELECT tarifa_id FROM tarifa t JOIN tipo_veiculo tv ON tv.tipo_id=t.tipo_id WHERE tv.nome='MOTO'");
        Savepoint sp = conexao.setSavepoint("antes_estac004");

        SQLException erro = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conexao.prepareStatement("""
                    INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id, entrada, valor, status, ticket_perdido)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0, 'ABERTA', FALSE)
                    """)) {
                ps.setInt(1, veiculoMoto);
                ps.setInt(2, vagaCarro);
                ps.setInt(3, tarifaMoto);
                ps.executeUpdate();
            }
        });

        assertEstac("ESTAC-004", erro);
        conexao.rollback(sp);
    }

    @Test
    void triggerValida_bloqueiaSobreposicaoVagaAberta_estac005() throws SQLException {
        // C010 (vaga_id 10) tem permanência ABERTA no seed (perm 18)
        int vagaOcupada = buscarId("SELECT vaga_id FROM vaga WHERE codigo = 'C010'");
        int tarifaCarro = buscarId("SELECT tarifa_id FROM tarifa t JOIN tipo_veiculo tv ON tv.tipo_id=t.tipo_id WHERE tv.nome='CARRO'");
        int veiculoNovo = inserirVeiculoCarro("TRG1A01");
        Savepoint sp = conexao.setSavepoint("antes_estac005");

        SQLException erro = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conexao.prepareStatement("""
                    INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id, entrada, valor, status, ticket_perdido)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0, 'ABERTA', FALSE)
                    """)) {
                ps.setInt(1, veiculoNovo);
                ps.setInt(2, vagaOcupada);
                ps.setInt(3, tarifaCarro);
                ps.executeUpdate();
            }
        });

        assertEstac("ESTAC-005", erro);
        conexao.rollback(sp);
    }

    @Test
    void triggerAuditoria_registraInsertEUpdateStatusValor() throws SQLException {
        int veiculo = inserirVeiculoCarro("AUD1A01");
        int vaga = buscarId("SELECT vaga_id FROM vaga WHERE codigo = 'C001'");
        int tarifa = buscarId("SELECT tarifa_id FROM tarifa t JOIN tipo_veiculo tv ON tv.tipo_id=t.tipo_id WHERE tv.nome='CARRO'");

        int permanenciaId;
        try (PreparedStatement ps = conexao.prepareStatement("""
                INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id, entrada, valor, status, ticket_perdido)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP, 0, 'ABERTA', FALSE)
                RETURNING permanencia_id
                """)) {
            ps.setInt(1, veiculo);
            ps.setInt(2, vaga);
            ps.setInt(3, tarifa);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                permanenciaId = rs.getInt(1);
            }
        }

        // auditoria do INSERT
        try (PreparedStatement ps = conexao.prepareStatement("""
                SELECT operacao, status_antes, status_depois, valor_antes, valor_depois
                FROM auditoria_permanencia
                WHERE permanencia_id = ?
                ORDER BY auditoria_id
                """)) {
            ps.setInt(1, permanenciaId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "auditoria de INSERT ausente");
                assertEquals("INSERT", rs.getString("operacao"));
                assertNull(rs.getString("status_antes"));
                assertEquals("ABERTA", rs.getString("status_depois"));
                assertNull(rs.getBigDecimal("valor_antes"));
                assertDecimal("0.00", rs.getBigDecimal("valor_depois"), "valor_depois INSERT");
                assertFalse(rs.next(), "INSERT deve gerar apenas 1 auditoria até aqui");
            }
        }

        // UPDATE de status e valor dispara auditoria AFTER UPDATE OF status, valor
        try (PreparedStatement up = conexao.prepareStatement("""
                UPDATE permanencia
                SET status = 'FECHADA', valor = ?, saida = entrada + INTERVAL '1 minute'
                WHERE permanencia_id = ?
                """)) {
            up.setBigDecimal(1, new BigDecimal("12.00"));
            up.setInt(2, permanenciaId);
            up.executeUpdate();
        }

        try (PreparedStatement ps = conexao.prepareStatement("""
                SELECT operacao, status_antes, status_depois, valor_antes, valor_depois
                FROM auditoria_permanencia
                WHERE permanencia_id = ?
                ORDER BY auditoria_id
                """)) {
            ps.setInt(1, permanenciaId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "auditoria de INSERT ausente");
                assertEquals("INSERT", rs.getString("operacao"));

                assertTrue(rs.next(), "auditoria de UPDATE ausente");
                assertEquals("UPDATE", rs.getString("operacao"));
                assertEquals("ABERTA", rs.getString("status_antes"));
                assertEquals("FECHADA", rs.getString("status_depois"));
                assertDecimal("0.00", rs.getBigDecimal("valor_antes"), "valor_antes UPDATE");
                assertDecimal("12.00", rs.getBigDecimal("valor_depois"), "valor_depois UPDATE");

                assertFalse(rs.next(), "INSERT + UPDATE de status/valor devem gerar 2 auditorias");
            }
        }
    }

    private int inserirVeiculoCarro(String placa) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(
                "INSERT INTO veiculo (placa, tipo_id) VALUES (?, 1) RETURNING veiculo_id")) {
            ps.setString(1, placa);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
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

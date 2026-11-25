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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A5 — valida as constraints do DDL (A2) contra o banco semeado (A3).
 * Cada teste roda em transação com rollback; após um erro esperado, o
 * savepoint devolve a transação a um estado utilizável antes do rollback final.
 */
class DdlConstraintTest {

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
    void constraintsSaoNomeadas() throws SQLException {
        Map<String, Integer> quantidades = new HashMap<>();
        try (PreparedStatement ps = conexao.prepareStatement("""
                SELECT contype, COUNT(*) FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace
                  AND contype IN ('p', 'u', 'f', 'c')
                GROUP BY contype
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                quantidades.put(rs.getString(1), rs.getInt(2));
            }
        }

        assertEquals(7, quantidades.get("p"), "PKs");
        assertEquals(5, quantidades.get("u"), "UKs");
        assertEquals(8, quantidades.get("f"), "FKs");
        assertEquals(15, quantidades.get("c"), "CHECKs");

        assertEquals(0, contar("""
                SELECT COUNT(*) FROM pg_constraint
                WHERE connamespace = 'public'::regnamespace
                  AND contype IN ('p', 'u', 'f', 'c')
                  AND conname !~ CASE contype
                        WHEN 'p' THEN '^pk_'
                        WHEN 'u' THEN '^uk_'
                        WHEN 'f' THEN '^fk_'
                        WHEN 'c' THEN '^ck_'
                      END
                """), "constraints fora do padrão de nome");
    }

    @Test
    void placaInvalidaBarrada() throws SQLException {
        int tipoCarro = buscarId("SELECT tipo_id FROM tipo_veiculo WHERE nome = 'CARRO'");
        Savepoint savepoint = conexao.setSavepoint("antes_placa_invalida");

        SQLException erro = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conexao.prepareStatement(
                    "INSERT INTO veiculo (placa, tipo_id) VALUES (?, ?)")) {
                ps.setString(1, "ABC-123");
                ps.setInt(2, tipoCarro);
                ps.executeUpdate();
            }
        });

        assertEquals("23514", erro.getSQLState());
        assertTrue(erro.getMessage().contains("ck_veiculo_placa"), erro.getMessage());
        conexao.rollback(savepoint);
    }

    @Test
    void saidaIgualOuAnteriorEntradaBarrada() throws SQLException {
        int veiculo = buscarId("SELECT veiculo_id FROM veiculo WHERE placa = 'ABC1D23'");
        int vaga = buscarId("SELECT vaga_id FROM vaga WHERE codigo = 'C001'");
        int tarifa = buscarId("SELECT tarifa_id FROM tarifa ORDER BY tarifa_id LIMIT 1");
        Savepoint savepoint = conexao.setSavepoint("antes_saida_invalida");

        SQLException erro = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conexao.prepareStatement("""
                    INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id,
                                             entrada, saida, valor, status, ticket_perdido)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 'FECHADA', FALSE)
                    """)) {
                ps.setInt(1, veiculo);
                ps.setInt(2, vaga);
                ps.setInt(3, tarifa);
                ps.executeUpdate();
            }
        });

        assertEquals("23514", erro.getSQLState());
        assertTrue(erro.getMessage().contains("ck_permanencia_saida"), erro.getMessage());
        conexao.rollback(savepoint);
    }

    @Test
    void pagamentoDuplicadoBarrado() throws SQLException {
        int permanencia = buscarId("""
                SELECT permanencia_id FROM permanencia
                WHERE valor > 0 ORDER BY permanencia_id LIMIT 1
                """);
        Savepoint savepoint = conexao.setSavepoint("antes_pagamento_duplicado");

        SQLException erro = assertThrows(SQLException.class, () -> {
            try (PreparedStatement ps = conexao.prepareStatement("""
                    INSERT INTO pagamento (permanencia_id, valor, forma, data_hora)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                    """)) {
                ps.setInt(1, permanencia);
                ps.setBigDecimal(2, new BigDecimal("1.00"));
                ps.setString(3, "PIX");
                ps.executeUpdate();
            }
        });

        assertEquals("23505", erro.getSQLState());
        assertTrue(erro.getMessage().contains("uk_pagamento_permanencia"), erro.getMessage());
        conexao.rollback(savepoint);
    }

    private int contar(String sql) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "consulta de contagem sem linha");
            return rs.getInt(1);
        }
    }

    private int buscarId(String sql) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next(), "dado do seed não encontrado: " + sql);
            return rs.getInt(1);
        }
    }
}

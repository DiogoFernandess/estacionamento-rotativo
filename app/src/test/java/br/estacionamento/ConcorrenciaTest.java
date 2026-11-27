package br.estacionamento;

import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C4 — 1 teste de concorrência: duas conexões disputam a última vaga livre.
 * Apenas uma sessão deve vencer; a outra recebe ESTAC-001.
 */
class ConcorrenciaTest {

    @Test
    void duasConexoesUltimaVaga_apenasUmaVence() throws Exception {
        try (Connection setup = abrirConexao()) {
            setup.setAutoCommit(false);
            try {
                ocupar15VagasMotoLivres(setup);
                setup.commit();

                CountDownLatch prontas = new CountDownLatch(2);
                CountDownLatch largada = new CountDownLatch(1);
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try (Connection c1 = abrirConexao(); Connection c2 = abrirConexao()) {
                    c1.setAutoCommit(false);
                    c2.setAutoCommit(false);

                    Future<Resultado> f1 = pool.submit(() -> tentarEntrada(c1, "COR1A01", prontas, largada));
                    Future<Resultado> f2 = pool.submit(() -> tentarEntrada(c2, "COR1A02", prontas, largada));

                    assertTrue(prontas.await(10, TimeUnit.SECONDS), "threads não ficaram prontas");
                    largada.countDown();

                    Resultado r1 = f1.get(30, TimeUnit.SECONDS);
                    Resultado r2 = f2.get(30, TimeUnit.SECONDS);

                    int sucessos = (r1.sucesso ? 1 : 0) + (r2.sucesso ? 1 : 0);
                    int estac001 = (r1.estac001 ? 1 : 0) + (r2.estac001 ? 1 : 0);
                    assertEquals(1, sucessos, "exatamente uma sessão deve vencer: " + r1 + " / " + r2);
                    assertEquals(1, estac001, "a outra sessão deve receber ESTAC-001: " + r1 + " / " + r2);

                    c1.rollback();
                    c2.rollback();
                } finally {
                    pool.shutdownNow();
                    pool.awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                limparDadosTeste(setup);
                setup.commit();
            }
        }
    }

    private Resultado tentarEntrada(Connection conn, String placa, CountDownLatch prontas, CountDownLatch largada) {
        try {
            prontas.countDown();
            assertTrue(largada.await(10, TimeUnit.SECONDS), "largada não ocorreu");
            try (CallableStatement cs = conn.prepareCall("CALL registrar_entrada(?, ?)")) {
                cs.setString(1, placa);
                cs.setString(2, "MOTO");
                cs.execute();
            }
            return Resultado.sucesso(placa);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if ("P0001".equals(e.getSQLState()) && msg.contains("ESTAC-001")) {
                return Resultado.estac001(placa, msg);
            }
            return Resultado.falha(placa, e.getSQLState(), msg);
        } catch (Exception e) {
            return Resultado.falha(placa, "", String.valueOf(e));
        }
    }

    private void ocupar15VagasMotoLivres(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO veiculo (placa, tipo_id)
                SELECT 'CON1A' || lpad(g::text, 2, '0'), 2
                FROM generate_series(1, 15) g
                """)) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id, entrada, valor, status, ticket_perdido)
                SELECT v.veiculo_id, va.vaga_id, 2, CURRENT_TIMESTAMP, 0, 'ABERTA', FALSE
                FROM (
                    SELECT veiculo_id, row_number() OVER (ORDER BY veiculo_id) rn
                    FROM veiculo WHERE placa LIKE 'CON1A%'
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
            int inseridas = ps.executeUpdate();
            assertEquals(15, inseridas, "deveriam ser ocupadas 15 vagas livres de moto");
        }
    }

    private void limparDadosTeste(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM auditoria_permanencia
                WHERE permanencia_id IN (
                    SELECT p.permanencia_id FROM permanencia p
                    JOIN veiculo v ON v.veiculo_id = p.veiculo_id
                    WHERE v.placa LIKE 'CON1A%' OR v.placa IN ('COR1A01', 'COR1A02')
                )
                """)) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM permanencia
                WHERE veiculo_id IN (
                    SELECT veiculo_id FROM veiculo
                    WHERE placa LIKE 'CON1A%' OR placa IN ('COR1A01', 'COR1A02')
                )
                """)) {
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                DELETE FROM veiculo
                WHERE placa LIKE 'CON1A%' OR placa IN ('COR1A01', 'COR1A02')
                """)) {
            ps.executeUpdate();
        }
    }

    private Connection abrirConexao() throws SQLException {
        String url = System.getenv().getOrDefault(
                "DB_URL", "jdbc:postgresql://localhost:5432/estacionamento");
        String usuario = System.getenv().getOrDefault("DB_USER", "estacionamento");
        String senha = System.getenv().getOrDefault("DB_PASSWORD", "estacionamento_pwd");
        return DriverManager.getConnection(url, usuario, senha);
    }

    private record Resultado(boolean sucesso, boolean estac001, String detalhe) {
        static Resultado sucesso(String placa) { return new Resultado(true, false, placa + ": OK"); }
        static Resultado estac001(String placa, String msg) { return new Resultado(false, true, placa + ": " + msg); }
        static Resultado falha(String placa, String sqlState, String msg) {
            return new Resultado(false, false, placa + ": SQLState=" + sqlState + " " + msg);
        }
    }
}

package br.estacionamento;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.util.Properties;

public class App {

    public static void main(String[] args) throws Exception {
        boolean efetivar = args.length > 0 && "--efetivar".equalsIgnoreCase(args[0]);
        String url = env("DB_URL", "jdbc:postgresql://localhost:5432/estacionamento");
        String usuario = env("DB_USER", "estacionamento");
        String senha = env("DB_PASSWORD", "estacionamento_pwd");

        Properties props = new Properties();
        props.setProperty("user", usuario);
        props.setProperty("password", senha);
        // decisions.md §6: CallableStatement com escapeSyntaxCallMode=callIfNoReturn
        props.setProperty("escapeSyntaxCallMode", "callIfNoReturn");

        try (Connection conn = DriverManager.getConnection(url, props)) {
            conn.setAutoCommit(false);
            System.out.println("=== Demo Estacionamento Rotativo Central ===");
            System.out.println("autoCommit=" + conn.getAutoCommit() + " | modo=" + (efetivar ? "COMMIT" : "ROLLBACK"));

            consultarVagasLivres(conn);
            int permanenciaEntrada = registrarEntradaFeliz(conn);
            int permanencia10h = consultarPermanenciasAbertas(conn);
            registrarSaida10h(conn, permanencia10h);
            registrarSaidaRepetida(conn, permanencia10h);
            registrarTipoIncompativel(conn);

            if (efetivar) {
                conn.commit();
                System.out.println("\n[TX] COMMIT efetuado (--efetivar).");
            } else {
                conn.rollback();
                System.out.println("\n[TX] ROLLBACK efetuado (padrão). Use --efetivar para persistir.");
            }

            // permanenciaEntrada pode ser -1 se falhou; apenas informativo
            if (permanenciaEntrada > 0) {
                System.out.println("[INFO] Permanência de entrada feliz no demo: " + permanenciaEntrada);
            }
        }
    }

    private static void consultarVagasLivres(Connection conn) throws SQLException {
        System.out.println("\n1) Consulta de vagas livres por tipo (PreparedStatement + bind)");
        String sql = """
                SELECT tv.nome AS tipo,
                       COUNT(*) FILTER (WHERE va.ativa
                           AND NOT EXISTS (
                               SELECT 1 FROM permanencia p
                               WHERE p.vaga_id = va.vaga_id AND p.status = 'ABERTA'
                           )) AS livres
                FROM vaga va
                JOIN tipo_veiculo tv ON tv.tipo_id = va.tipo_id
                WHERE va.ativa = ?
                GROUP BY tv.nome
                ORDER BY tv.nome
                """;
        Savepoint sp = conn.setSavepoint("sp_vagas_livres");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBoolean(1, true); // bind
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf("   - %s: %d vagas livres%n", rs.getString("tipo"), rs.getInt("livres"));
                }
            }
            liberarSavepoint(conn, sp);
        } catch (SQLException e) {
            conn.rollback(sp);
            throw e;
        }
    }

    private static int registrarEntradaFeliz(Connection conn) throws SQLException {
        System.out.println("\n2) Entrada feliz (CallableStatement)");
        Savepoint sp = conn.setSavepoint("sp_entrada_feliz");
        try (CallableStatement cs = conn.prepareCall("CALL registrar_entrada(?, ?)")) {
            cs.setString(1, "APP1A01");
            cs.setString(2, "CARRO");
            cs.execute();
        } catch (SQLException e) {
            conn.rollback(sp);
            System.out.println("   ERRO: " + traduzirErro(e));
            return -1;
        }

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.permanencia_id, v.placa, va.codigo, p.status, p.valor
                FROM permanencia p
                JOIN veiculo v ON v.veiculo_id = p.veiculo_id
                JOIN vaga va ON va.vaga_id = p.vaga_id
                WHERE v.placa = ? AND p.status = 'ABERTA'
                ORDER BY p.permanencia_id DESC LIMIT 1
                """)) {
            ps.setString(1, "APP1A01");
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt("permanencia_id");
                    System.out.printf("   OK permanencia=%d placa=%s vaga=%s status=%s valor=%s%n",
                            id, rs.getString("placa"), rs.getString("codigo"),
                            rs.getString("status"), rs.getBigDecimal("valor").toPlainString());
                    liberarSavepoint(conn, sp);
                    return id;
                }
            }
        }
        liberarSavepoint(conn, sp);
        System.out.println("   AVISO: entrada executada, mas permanência não localizada.");
        return -1;
    }

    private static int consultarPermanenciasAbertas(Connection conn) throws SQLException {
        System.out.println("\n3) Consulta permanências abertas (PreparedStatement + bind)");
        String sql = """
                SELECT p.permanencia_id, v.placa, tv.nome AS tipo, va.codigo AS vaga, p.entrada
                FROM permanencia p
                JOIN veiculo v ON v.veiculo_id = p.veiculo_id
                JOIN tipo_veiculo tv ON tv.tipo_id = v.tipo_id
                JOIN vaga va ON va.vaga_id = p.vaga_id
                WHERE p.status = ?
                ORDER BY p.entrada
                """;
        Savepoint sp = conn.setSavepoint("sp_abertas");
        int permanencia10h = -1;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, "ABERTA"); // bind
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("permanencia_id");
                    Timestamp entrada = rs.getTimestamp("entrada");
                    System.out.printf("   - #%d placa=%s tipo=%s vaga=%s entrada=%s%n",
                            id, rs.getString("placa"), rs.getString("tipo"),
                            rs.getString("vaga"), entrada);
                    if (permanencia10h < 0) {
                        permanencia10h = id; // mais antiga = 10h do seed
                    }
                }
            }
            liberarSavepoint(conn, sp);
        } catch (SQLException e) {
            conn.rollback(sp);
            throw e;
        }
        System.out.println("   Permanência de 10h selecionada: #" + permanencia10h);
        return permanencia10h;
    }

    private static void registrarSaida10h(Connection conn, int permanenciaId) throws SQLException {
        System.out.println("\n4) Saída da permanência de 10h (teto R$ 45,00)");
        Savepoint sp = conn.setSavepoint("sp_saida_10h");
        try (CallableStatement cs = conn.prepareCall("CALL registrar_saida(?, ?, ?)")) {
            cs.setInt(1, permanenciaId);
            cs.setString(2, "PIX");
            cs.setBoolean(3, false);
            cs.execute();
        } catch (SQLException e) {
            conn.rollback(sp);
            System.out.println("   ERRO: " + traduzirErro(e));
            return;
        }

        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.status, p.valor, pg.forma, pg.valor AS pagamento_valor
                FROM permanencia p
                LEFT JOIN pagamento pg ON pg.permanencia_id = p.permanencia_id
                WHERE p.permanencia_id = ?
                """)) {
            ps.setInt(1, permanenciaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    BigDecimal valor = rs.getBigDecimal("valor");
                    BigDecimal pago = rs.getBigDecimal("pagamento_valor");
                    System.out.printf("   OK status=%s valor=%s forma=%s pagamento=%s%n",
                            rs.getString("status"),
                            valor == null ? "null" : valor.toPlainString(),
                            rs.getString("forma"),
                            pago == null ? "null" : pago.toPlainString());
                }
            }
        }
        liberarSavepoint(conn, sp);
    }

    private static void registrarSaidaRepetida(Connection conn, int permanenciaId) throws SQLException {
        System.out.println("\n5) Saída repetida (esperado ESTAC-002)");
        Savepoint sp = conn.setSavepoint("sp_saida_repetida");
        try (CallableStatement cs = conn.prepareCall("CALL registrar_saida(?, ?, ?)")) {
            cs.setInt(1, permanenciaId);
            cs.setString(2, "PIX");
            cs.setBoolean(3, false);
            cs.execute();
            liberarSavepoint(conn, sp);
            System.out.println("   INESPERADO: saída repetida não falhou");
        } catch (SQLException e) {
            conn.rollback(sp);
            System.out.println("   ERRO DE NEGÓCIO CAPTURADO: " + traduzirErro(e));
        }
    }

    private static void registrarTipoIncompativel(Connection conn) throws SQLException {
        System.out.println("\n6) Tipo incompatível (esperado ESTAC-004)");
        Savepoint sp = conn.setSavepoint("sp_tipo_incompativel");
        try (CallableStatement cs = conn.prepareCall("CALL registrar_entrada(?, ?)")) {
            cs.setString(1, "XYZ1K11"); // moto no seed
            cs.setString(2, "CARRO");
            cs.execute();
            liberarSavepoint(conn, sp);
            System.out.println("   INESPERADO: tipo incompatível não falhou");
        } catch (SQLException e) {
            conn.rollback(sp);
            System.out.println("   ERRO DE NEGÓCIO CAPTURADO: " + traduzirErro(e));
        }
    }

    private static String traduzirErro(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        int i = msg.indexOf("ESTAC-");
        if (i >= 0) {
            int fim = msg.indexOf('\n', i);
            String trecho = fim >= 0 ? msg.substring(i, fim) : msg.substring(i);
            return trecho.trim() + " [SQLState=" + e.getSQLState() + "]";
        }
        return "ERRO SQL: " + msg + " [SQLState=" + e.getSQLState() + "]";
    }

    private static void liberarSavepoint(Connection conn, Savepoint sp) {
        try {
            conn.releaseSavepoint(sp);
        } catch (SQLException ignored) {
            // savepoint já pode ter sido liberado/invalidado; rollback da tx cuida
        }
    }

    private static String env(String chave, String padrao) {
        String valor = System.getenv(chave);
        return (valor == null || valor.isBlank()) ? padrao : valor;
    }
}

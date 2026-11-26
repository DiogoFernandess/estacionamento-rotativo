-- =====================================================================
-- Estacionamento Rotativo Central — índices de performance (Frente B / B4)
-- Roda no init depois de 01_ddl, 02_inserts e procedures.
-- O índice único parcial uk_permanencia_veiculo_aberta (RN-07) já vem do
-- DDL; aqui entram apenas os três índices de consulta/medicao.
-- IF NOT EXISTS permite reaplicar o arquivo sem DROP.
-- =====================================================================

CREATE INDEX IF NOT EXISTS idx_permanencia_veiculo
    ON permanencia (veiculo_id);

CREATE INDEX IF NOT EXISTS idx_permanencia_vaga_entrada
    ON permanencia (vaga_id, entrada);

CREATE INDEX IF NOT EXISTS idx_permanencia_status_entrada
    ON permanencia (status, entrada);

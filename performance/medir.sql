-- =====================================================================
-- Estacionamento Rotativo Central — massa de dados para medição (B5)
-- Esquema ISOLADO: não cria, altera ou derruba índice no esquema public.
-- As tabelas replicam as colunas de decisions.md §3; não há FKs/CHECKs
-- porque o objetivo é somente medir planos, não validar regra de negócio.
-- Rode uma vez; as inserções usam NOT EXISTS para não duplicar a massa.
-- Depois: EXPLAIN antes -> CREATE INDEX no esquema medicao -> EXPLAIN depois.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS medicao;

CREATE TABLE IF NOT EXISTS medicao.tipo_veiculo (
    tipo_id INTEGER,
    nome    VARCHAR(5),
    CONSTRAINT pk_medicao_tipo_veiculo PRIMARY KEY (tipo_id),
    CONSTRAINT uk_medicao_tipo_veiculo_nome UNIQUE (nome)
);

CREATE TABLE IF NOT EXISTS medicao.vaga (
    vaga_id INTEGER,
    codigo  VARCHAR(4),
    tipo_id INTEGER,
    ativa   BOOLEAN,
    CONSTRAINT pk_medicao_vaga PRIMARY KEY (vaga_id),
    CONSTRAINT uk_medicao_vaga_codigo UNIQUE (codigo)
);

CREATE TABLE IF NOT EXISTS medicao.veiculo (
    veiculo_id INTEGER,
    placa      VARCHAR(7),
    tipo_id    INTEGER,
    CONSTRAINT pk_medicao_veiculo PRIMARY KEY (veiculo_id),
    CONSTRAINT uk_medicao_veiculo_placa UNIQUE (placa)
);

CREATE TABLE IF NOT EXISTS medicao.tarifa (
    tarifa_id       INTEGER,
    tipo_id         INTEGER,
    vigencia_inicio TIMESTAMP,
    tolerancia_min  INTEGER,
    primeira_hora   NUMERIC(10,2),
    fracao_min      INTEGER,
    fracao_valor    NUMERIC(10,2),
    teto            NUMERIC(10,2),
    ticket_perdido  NUMERIC(10,2),
    CONSTRAINT pk_medicao_tarifa PRIMARY KEY (tarifa_id),
    CONSTRAINT uk_medicao_tarifa_tipo_vigencia UNIQUE (tipo_id, vigencia_inicio)
);

CREATE TABLE IF NOT EXISTS medicao.permanencia (
    permanencia_id INTEGER,
    veiculo_id     INTEGER,
    vaga_id        INTEGER,
    tarifa_id      INTEGER,
    entrada        TIMESTAMP,
    saida          TIMESTAMP,
    valor          NUMERIC(10,2),
    status         VARCHAR(7),
    ticket_perdido BOOLEAN,
    CONSTRAINT pk_medicao_permanencia PRIMARY KEY (permanencia_id)
);

CREATE TABLE IF NOT EXISTS medicao.pagamento (
    pagamento_id   INTEGER,
    permanencia_id INTEGER,
    valor          NUMERIC(10,2),
    forma          VARCHAR(8),
    data_hora      TIMESTAMP,
    CONSTRAINT pk_medicao_pagamento PRIMARY KEY (pagamento_id),
    CONSTRAINT uk_medicao_pagamento_permanencia UNIQUE (permanencia_id)
);

CREATE TABLE IF NOT EXISTS medicao.auditoria_permanencia (
    auditoria_id   INTEGER,
    permanencia_id INTEGER,
    operacao       VARCHAR(6),
    status_antes   VARCHAR(7),
    status_depois  VARCHAR(7),
    valor_antes    NUMERIC(10,2),
    valor_depois   NUMERIC(10,2),
    usuario_bd     VARCHAR(63),
    data_hora      TIMESTAMP,
    CONSTRAINT pk_medicao_auditoria PRIMARY KEY (auditoria_id)
);

-- Dimensões: 2 tipos, 120 vagas e 2 tarifas copiadas do esquema principal
-- (somente leitura) para manter os códigos/tipos do seed original.
INSERT INTO medicao.tipo_veiculo (tipo_id, nome)
SELECT t.tipo_id, t.nome
FROM (VALUES (1, 'CARRO'), (2, 'MOTO')) AS t(tipo_id, nome)
WHERE NOT EXISTS (SELECT 1 FROM medicao.tipo_veiculo);

INSERT INTO medicao.vaga (vaga_id, codigo, tipo_id, ativa)
SELECT v.vaga_id, v.codigo, v.tipo_id, v.ativa
FROM public.vaga v
WHERE NOT EXISTS (SELECT 1 FROM medicao.vaga);

INSERT INTO medicao.tarifa (
    tarifa_id, tipo_id, vigencia_inicio, tolerancia_min, primeira_hora,
    fracao_min, fracao_valor, teto, ticket_perdido
)
SELECT t.tarifa_id, t.tipo_id, t.vigencia_inicio, t.tolerancia_min,
       t.primeira_hora, t.fracao_min, t.fracao_valor, t.teto, t.ticket_perdido
FROM public.tarifa t
WHERE NOT EXISTS (SELECT 1 FROM medicao.tarifa);

INSERT INTO medicao.veiculo (veiculo_id, placa, tipo_id)
SELECT g,
       'VE' || lpad(g::text, 5, '0'),
       CASE WHEN g % 5 = 0 THEN 2 ELSE 1 END
FROM generate_series(1, 12000) AS g
WHERE NOT EXISTS (SELECT 1 FROM medicao.veiculo);

-- 132.000 permanências: 120.000 FECHADA (com pagamento) + 12.000 ABERTA.
-- Cada vaga recebe ~1.100 permanências; as datas se espalham por 300 horas.
INSERT INTO medicao.permanencia (
    permanencia_id, veiculo_id, vaga_id, tarifa_id,
    entrada, saida, valor, status, ticket_perdido
)
SELECT g,
       CASE WHEN g <= 120000 THEN ((g - 1) % 12000) + 1 ELSE g - 120000 END,
       ((g - 1) % 120) + 1,
       CASE WHEN vg.tipo_id = 1 THEN 1 ELSE 2 END,
       TIMESTAMP '2026-01-01 00:00:00' + ((g % 300) * INTERVAL '1 hour'),
       CASE WHEN g <= 120000
            THEN TIMESTAMP '2026-01-01 00:00:00'
                 + ((g % 300) * INTERVAL '1 hour') + INTERVAL '1 hour'
       END,
       CASE WHEN g <= 120000 THEN 12.00 ELSE 0 END,
       CASE WHEN g <= 120000 THEN 'FECHADA' ELSE 'ABERTA' END,
       FALSE
FROM generate_series(1, 132000) AS g
JOIN medicao.vaga vg ON vg.vaga_id = ((g - 1) % 120) + 1
WHERE NOT EXISTS (SELECT 1 FROM medicao.permanencia);

-- 120.000 pagamentos, um por permanência FECHADA (valor > 0, RN-09).
INSERT INTO medicao.pagamento (pagamento_id, permanencia_id, valor, forma, data_hora)
SELECT g,
       g,
       12.00,
       CASE g % 3 WHEN 0 THEN 'CARTAO' WHEN 1 THEN 'PIX' ELSE 'DINHEIRO' END,
       TIMESTAMP '2026-01-01 01:00:00' + ((g % 300) * INTERVAL '1 hour')
FROM generate_series(1, 120000) AS g
WHERE NOT EXISTS (SELECT 1 FROM medicao.pagamento);

-- Estatísticas para o planejador escolher planos com dados reais.
ANALYZE medicao.tipo_veiculo;
ANALYZE medicao.vaga;
ANALYZE medicao.veiculo;
ANALYZE medicao.tarifa;
ANALYZE medicao.permanencia;
ANALYZE medicao.pagamento;
ANALYZE medicao.auditoria_permanencia;

SELECT (SELECT COUNT(*) FROM medicao.permanencia) AS permanencias,
       (SELECT COUNT(*) FROM medicao.pagamento)   AS pagamentos,
       (SELECT COUNT(*) FROM medicao.vaga)        AS vagas;

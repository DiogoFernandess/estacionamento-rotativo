-- =====================================================================
-- Estacionamento Rotativo Central — consultas Q01..Q10 (Frente B / B1)
-- Fonte de verdade: decisions.md §5 (objetivo, técnica e critérios).
-- Cada consulta tem o marcador -- @Qnn e termina com ';'.
-- Este arquivo NÃO roda no init; é executado no psql e lido pelos testes.
-- =====================================================================

-- @Q01
-- Ocupação atual por tipo: vagas ativas, permanências ABERTA e percentual.
SELECT tv.nome AS tipo,
       COUNT(DISTINCT va.vaga_id) AS total,
       COUNT(DISTINCT p.permanencia_id) AS ocupadas,
       COUNT(DISTINCT va.vaga_id) - COUNT(DISTINCT p.permanencia_id) AS livres,
       ROUND(100.0 * COUNT(DISTINCT p.permanencia_id)
             / NULLIF(COUNT(DISTINCT va.vaga_id), 0), 2) AS percentual_ocupacao
FROM vaga va
JOIN tipo_veiculo tv ON tv.tipo_id = va.tipo_id
LEFT JOIN permanencia p
       ON p.vaga_id = va.vaga_id
      AND p.status = 'ABERTA'
WHERE va.ativa
GROUP BY tv.nome
ORDER BY tv.nome;

-- @Q02
-- Receita diária dos últimos 30 dias com acumulado (dias sem pagamento não aparecem).
WITH diario AS (
    SELECT data_hora::date AS dia,
           SUM(valor) AS receita
    FROM pagamento
    WHERE data_hora >= CURRENT_DATE - INTERVAL '29 days'
      AND data_hora < CURRENT_DATE + INTERVAL '1 day'
    GROUP BY data_hora::date
)
SELECT dia,
       receita,
       SUM(receita) OVER (ORDER BY dia
                          ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS acumulado
FROM diario
ORDER BY dia;

-- @Q03
-- Top 10 por gasto: RANK() mantém empate na posição de corte.
WITH gasto AS (
    SELECT v.veiculo_id,
           v.placa,
           SUM(pg.valor) AS gasto
    FROM pagamento pg
    JOIN permanencia p ON p.permanencia_id = pg.permanencia_id
    JOIN veiculo v ON v.veiculo_id = p.veiculo_id
    GROUP BY v.veiculo_id, v.placa
), ranqueado AS (
    SELECT placa,
           gasto,
           RANK() OVER (ORDER BY gasto DESC) AS posicao
    FROM gasto
)
SELECT posicao, placa, gasto
FROM ranqueado
WHERE posicao <= 10
ORDER BY posicao, placa;

-- @Q04
-- Tempo médio, mínimo e máximo em minutos por tipo (somente FECHADA).
SELECT tv.nome AS tipo,
       ROUND(AVG(EXTRACT(EPOCH FROM (p.saida - p.entrada)) / 60), 2) AS media_min,
       ROUND(MIN(EXTRACT(EPOCH FROM (p.saida - p.entrada)) / 60), 2) AS minimo_min,
       ROUND(MAX(EXTRACT(EPOCH FROM (p.saida - p.entrada)) / 60), 2) AS maximo_min
FROM permanencia p
JOIN veiculo v ON v.veiculo_id = p.veiculo_id
JOIN tipo_veiculo tv ON tv.tipo_id = v.tipo_id
WHERE p.status = 'FECHADA'
GROUP BY tv.nome
ORDER BY tv.nome;

-- @Q05
-- Movimento por hora do dia: entradas e saídas somadas após UNION ALL.
SELECT hora,
       SUM(entradas) AS entradas,
       SUM(saidas) AS saidas
FROM (
    SELECT EXTRACT(HOUR FROM entrada)::integer AS hora,
           1 AS entradas,
           0 AS saidas
    FROM permanencia
    UNION ALL
    SELECT EXTRACT(HOUR FROM saida)::integer AS hora,
           0 AS entradas,
           1 AS saidas
    FROM permanencia
    WHERE saida IS NOT NULL
) movimento
GROUP BY hora
ORDER BY hora;

-- @Q06
-- Receita por forma de pagamento e percentual sobre o total.
SELECT forma,
       SUM(valor) AS receita,
       ROUND(100 * SUM(valor) / SUM(SUM(valor)) OVER (), 2) AS percentual
FROM pagamento
GROUP BY forma
ORDER BY forma;

-- @Q07
-- Vagas ativas que nunca receberam permanência (qualquer status).
SELECT va.codigo,
       tv.nome AS tipo
FROM vaga va
JOIN tipo_veiculo tv ON tv.tipo_id = va.tipo_id
WHERE va.ativa
  AND NOT EXISTS (
      SELECT 1
      FROM permanencia p
      WHERE p.vaga_id = va.vaga_id
  )
ORDER BY va.codigo;

-- @Q08
-- Tolerância, ticket perdido e cobrado nas permanências fechadas.
SELECT CASE
           WHEN p.ticket_perdido THEN 'TICKET_PERDIDO'
           WHEN EXTRACT(EPOCH FROM (p.saida - p.entrada)) / 60 <= t.tolerancia_min
                THEN 'TOLERANCIA'
           ELSE 'COBRADO'
       END AS categoria,
       COUNT(*) AS quantidade,
       SUM(p.valor) AS receita
FROM permanencia p
JOIN tarifa t ON t.tarifa_id = p.tarifa_id
WHERE p.status = 'FECHADA'
GROUP BY 1
ORDER BY 1;

-- @Q09
-- Receita mensal e variação percentual em relação ao mês anterior.
WITH mensal AS (
    SELECT DATE_TRUNC('month', data_hora)::date AS mes,
           SUM(valor) AS receita
    FROM pagamento
    GROUP BY 1
), com_anterior AS (
    SELECT mes,
           receita,
           LAG(receita) OVER (ORDER BY mes) AS receita_anterior
    FROM mensal
)
SELECT mes,
       receita,
       receita_anterior,
       ROUND(100 * (receita - receita_anterior)
             / NULLIF(receita_anterior, 0), 2) AS variacao_percentual
FROM com_anterior
ORDER BY mes;

-- @Q10
-- Receita por tipo e forma com subtotais e total geral (ROLLUP).
SELECT tv.nome AS tipo,
       pg.forma AS forma,
       SUM(pg.valor) AS receita
FROM pagamento pg
JOIN permanencia p ON p.permanencia_id = pg.permanencia_id
JOIN veiculo v ON v.veiculo_id = p.veiculo_id
JOIN tipo_veiculo tv ON tv.tipo_id = v.tipo_id
GROUP BY ROLLUP(tv.nome, pg.forma)
ORDER BY GROUPING(tv.nome), tv.nome, GROUPING(pg.forma), pg.forma;

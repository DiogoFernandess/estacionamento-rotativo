-- =====================================================================
-- Estacionamento Rotativo Central — dados de exemplo (Frente A / A3)
-- Fonte de verdade: decisions.md §2 (regras), §3 (modelo) e §5
-- (fronteiras obrigatórias do seed).
--
-- Roda depois do 01_ddl.sql e antes das procedures/índices.
-- Datas relativas a CURRENT_DATE/CURRENT_TIMESTAMP; placas fictícias.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Tipos de veículo (2)
-- ---------------------------------------------------------------------
INSERT INTO tipo_veiculo (nome)
VALUES ('CARRO'), ('MOTO');

-- ---------------------------------------------------------------------
-- 2. Tarifas (insert-only: 1 linha por tipo, com vigência no passado)
--    CARRO (RN-03): 1ª hora 12,00 | fração 30 min 4,00 | teto 45,00 | perdido 60,00
--    MOTO  (RN-04): 1ª hora  8,00 | fração 30 min 3,00 | teto 30,00 | perdido 40,00
--    Tolerância 15 min nos dois tipos (RN-02).
-- ---------------------------------------------------------------------
INSERT INTO tarifa (tipo_id, vigencia_inicio, tolerancia_min,
                    primeira_hora, fracao_min, fracao_valor, teto, ticket_perdido)
SELECT t.tipo_id, TIMESTAMP '2025-01-01 00:00:00', v.tolerancia_min,
       v.primeira_hora, v.fracao_min, v.fracao_valor, v.teto, v.ticket_perdido
FROM (VALUES
        ('CARRO', 15, 12.00, 30, 4.00, 45.00, 60.00),
        ('MOTO',  15,  8.00, 30, 3.00, 30.00, 40.00)
     ) AS v(tipo, tolerancia_min, primeira_hora, fracao_min, fracao_valor, teto, ticket_perdido)
JOIN tipo_veiculo t ON t.nome = v.tipo;

-- ---------------------------------------------------------------------
-- 3. Vagas: 100 de carro (C001–C100) e 20 de moto (M001–M020), todas ativas.
--    C100 e M020 nunca recebem permanência (Q07).
-- ---------------------------------------------------------------------
INSERT INTO vaga (codigo, tipo_id, ativa)
SELECT 'C' || LPAD(n::text, 3, '0'), t.tipo_id, TRUE
FROM generate_series(1, 100) AS n
CROSS JOIN tipo_veiculo t
WHERE t.nome = 'CARRO';

INSERT INTO vaga (codigo, tipo_id, ativa)
SELECT 'M' || LPAD(n::text, 3, '0'), t.tipo_id, TRUE
FROM generate_series(1, 20) AS n
CROSS JOIN tipo_veiculo t
WHERE t.nome = 'MOTO';

-- ---------------------------------------------------------------------
-- 4. Veículos fictícios (31): 19 carros e 12 motos.
--    19 carros = 9 com permanência fechada + 10 com permanência aberta.
--    12 motos  = 8 com permanência fechada +  4 com permanência aberta.
--    Cada veículo tem UMA permanência no seed (facilita a leitura dos
--    resultados esperados das consultas).
-- ---------------------------------------------------------------------
INSERT INTO veiculo (placa, tipo_id)
SELECT v.placa, t.tipo_id
FROM (VALUES
        -- carros com permanência fechada (C001–C009)
        ('ABC1D23', 'CARRO'),
        ('ABC2D34', 'CARRO'),
        ('ABC3D45', 'CARRO'),
        ('ABC4D56', 'CARRO'),
        ('ABC5D67', 'CARRO'),
        ('ABC6D78', 'CARRO'),
        ('ABC7D89', 'CARRO'),
        ('ABC8D90', 'CARRO'),
        ('ABC9E01', 'CARRO'),
        -- carros com permanência aberta (C010–C019)
        ('QRS1A11', 'CARRO'),
        ('QRS2B22', 'CARRO'),
        ('QRS3C33', 'CARRO'),
        ('QRS4D44', 'CARRO'),
        ('QRS5E55', 'CARRO'),
        ('QRS6F66', 'CARRO'),
        ('QRS7G77', 'CARRO'),
        ('QRS8H88', 'CARRO'),
        ('QRS9I99', 'CARRO'),
        ('QRS0J10', 'CARRO'),
        -- motos com permanência fechada (M001–M008)
        ('MOT1A23', 'MOTO'),
        ('MOT2B34', 'MOTO'),
        ('MOT3C45', 'MOTO'),
        ('MOT4D56', 'MOTO'),
        ('MOT5E67', 'MOTO'),
        ('MOT6F78', 'MOTO'),
        ('MOT7G89', 'MOTO'),
        ('MOT8H90', 'MOTO'),
        -- motos com permanência aberta (M009–M012)
        ('XYZ1K11', 'MOTO'),
        ('XYZ2L22', 'MOTO'),
        ('XYZ3M33', 'MOTO'),
        ('XYZ4N44', 'MOTO')
     ) AS v(placa, tipo)
JOIN tipo_veiculo t ON t.nome = v.tipo;

-- ---------------------------------------------------------------------
-- 5. Permanências FECHADAS — fronteiras das RN-02/03/04/05
--
--    Duração exata (saida = entrada + duracao) e valor esperado:
--      CARRO                                   MOTO
--      15:00          ->   0,00 (tolerância)   15:00          ->  0,00
--      15:01          ->  12,00 (1ª hora)      15:01          ->  8,00
--      60:00          ->  12,00                60:00          ->  8,00
--      60:01          ->  16,00                60:01          -> 11,00
--      90:00          ->  16,00                90:00          -> 11,00
--      90:01          ->  20,00                90:01          -> 14,00
--      300:00         ->  44,00                300:00         -> 30,00 (teto)
--      300:01         ->  45,00 (teto)         ticket perdido -> 40,00
--      ticket perdido ->  60,00
--
--    300:00/300:01 ficam no mês anterior para Q09 ter dois meses.
--    Valor 0 não gera linha em pagamento (RN-09).
-- ---------------------------------------------------------------------
INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id,
                         entrada, saida, valor, status, ticket_perdido)
SELECT ve.veiculo_id, va.vaga_id, ta.tarifa_id,
       d.inicio,
       d.inicio + d.duracao,
       d.valor,
       'FECHADA',
       d.ticket_perdido
FROM (VALUES
        -- carros: fronteiras
        ('ABC1D23', 'C001', 'CARRO', CURRENT_DATE - 2 + TIME '08:00', INTERVAL '15 minutes',            0.00, FALSE),
        ('ABC2D34', 'C002', 'CARRO', CURRENT_DATE - 2 + TIME '08:00', INTERVAL '15 minutes 1 second',  12.00, FALSE),
        ('ABC3D45', 'C003', 'CARRO', CURRENT_DATE - 3 + TIME '09:00', INTERVAL '60 minutes',           12.00, FALSE),
        ('ABC4D56', 'C004', 'CARRO', CURRENT_DATE - 3 + TIME '09:00', INTERVAL '60 minutes 1 second',  16.00, FALSE),
        ('ABC5D67', 'C005', 'CARRO', CURRENT_DATE - 4 + TIME '10:00', INTERVAL '90 minutes',           16.00, FALSE),
        ('ABC6D78', 'C006', 'CARRO', CURRENT_DATE - 4 + TIME '10:00', INTERVAL '90 minutes 1 second',  20.00, FALSE),
        ('ABC7D89', 'C007', 'CARRO', CURRENT_DATE - 1 + TIME '06:00', INTERVAL '300 minutes',          44.00, FALSE),
        ('ABC8D90', 'C008', 'CARRO', DATE_TRUNC('month', CURRENT_DATE)::date - 10 + TIME '09:00',
                                                                      INTERVAL '300 minutes 1 second', 45.00, FALSE),
        ('ABC9E01', 'C009', 'CARRO', CURRENT_DATE - 1 + TIME '12:00', INTERVAL '45 minutes',           60.00, TRUE),
        -- motos: fronteiras
        ('MOT1A23', 'M001', 'MOTO',  CURRENT_DATE - 2 + TIME '08:00', INTERVAL '15 minutes',            0.00, FALSE),
        ('MOT2B34', 'M002', 'MOTO',  CURRENT_DATE - 2 + TIME '08:00', INTERVAL '15 minutes 1 second',   8.00, FALSE),
        ('MOT3C45', 'M003', 'MOTO',  CURRENT_DATE - 3 + TIME '09:00', INTERVAL '60 minutes',            8.00, FALSE),
        ('MOT4D56', 'M004', 'MOTO',  CURRENT_DATE - 3 + TIME '09:00', INTERVAL '60 minutes 1 second',  11.00, FALSE),
        ('MOT5E67', 'M005', 'MOTO',  CURRENT_DATE - 5 + TIME '11:00', INTERVAL '90 minutes',           11.00, FALSE),
        ('MOT6F78', 'M006', 'MOTO',  CURRENT_DATE - 5 + TIME '11:00', INTERVAL '90 minutes 1 second',  14.00, FALSE),
        ('MOT7G89', 'M007', 'MOTO',  DATE_TRUNC('month', CURRENT_DATE)::date - 10 + TIME '08:00',
                                                                      INTERVAL '300 minutes',          30.00, FALSE),
        ('MOT8H90', 'M008', 'MOTO',  DATE_TRUNC('month', CURRENT_DATE)::date - 10 + TIME '14:00',
                                                                      INTERVAL '45 minutes',           40.00, TRUE)
     ) AS d(placa, codigo, tipo, inicio, duracao, valor, ticket_perdido)
JOIN veiculo ve ON ve.placa = d.placa
JOIN vaga    va ON va.codigo = d.codigo
JOIN tarifa  ta ON ta.tipo_id = (SELECT tipo_id FROM tipo_veiculo WHERE nome = d.tipo);

-- ---------------------------------------------------------------------
-- 6. Pagamentos das permanências fechadas com valor > 0 (RN-08/RN-09).
--    data_hora = saída da permanência. As três formas aparecem (Q06).
--    Carro: DINHEIRO 12+16+60 | PIX 12+20+45 | CARTAO 16+44
--    Moto : DINHEIRO  8+14    | PIX  8+11+40 | CARTAO 11+30
-- ---------------------------------------------------------------------
INSERT INTO pagamento (permanencia_id, valor, forma, data_hora)
SELECT p.permanencia_id, pg.valor, pg.forma, p.saida
FROM (VALUES
        ('C002', 12.00, 'DINHEIRO'),
        ('C003', 12.00, 'PIX'),
        ('C004', 16.00, 'CARTAO'),
        ('C005', 16.00, 'DINHEIRO'),
        ('C006', 20.00, 'PIX'),
        ('C007', 44.00, 'CARTAO'),
        ('C008', 45.00, 'PIX'),
        ('C009', 60.00, 'DINHEIRO'),
        ('M002',  8.00, 'PIX'),
        ('M003',  8.00, 'DINHEIRO'),
        ('M004', 11.00, 'CARTAO'),
        ('M005', 11.00, 'PIX'),
        ('M006', 14.00, 'DINHEIRO'),
        ('M007', 30.00, 'CARTAO'),
        ('M008', 40.00, 'PIX')
     ) AS pg(codigo, valor, forma)
JOIN permanencia p ON p.vaga_id = (SELECT vaga_id FROM vaga WHERE codigo = pg.codigo);

-- ---------------------------------------------------------------------
-- 7. Permanências ABERTAS: 10 carros e 4 motos (Q01 = 10/100 e 4/20).
--    Uma aberta há 10 horas para o demo do app (fecha no teto R$ 45,00).
-- ---------------------------------------------------------------------
INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id, entrada, valor, status)
SELECT ve.veiculo_id, va.vaga_id, ta.tarifa_id, d.entrada, 0, 'ABERTA'
FROM (VALUES
        ('QRS1A11', 'C010', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '10 hours'),
        ('QRS2B22', 'C011', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '15 minutes'),
        ('QRS3C33', 'C012', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '30 minutes'),
        ('QRS4D44', 'C013', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '1 hour'),
        ('QRS5E55', 'C014', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '2 hours'),
        ('QRS6F66', 'C015', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '3 hours'),
        ('QRS7G77', 'C016', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '4 hours'),
        ('QRS8H88', 'C017', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '5 hours'),
        ('QRS9I99', 'C018', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '6 hours'),
        ('QRS0J10', 'C019', 'CARRO', CURRENT_TIMESTAMP - INTERVAL '8 hours'),
        ('XYZ1K11', 'M009', 'MOTO',  CURRENT_TIMESTAMP - INTERVAL '20 minutes'),
        ('XYZ2L22', 'M010', 'MOTO',  CURRENT_TIMESTAMP - INTERVAL '45 minutes'),
        ('XYZ3M33', 'M011', 'MOTO',  CURRENT_TIMESTAMP - INTERVAL '2 hours'),
        ('XYZ4N44', 'M012', 'MOTO',  CURRENT_TIMESTAMP - INTERVAL '7 hours')
     ) AS d(placa, codigo, tipo, entrada)
JOIN veiculo ve ON ve.placa = d.placa
JOIN vaga    va ON va.codigo = d.codigo
JOIN tarifa  ta ON ta.tipo_id = (SELECT tipo_id FROM tipo_veiculo WHERE nome = d.tipo);

-- ---------------------------------------------------------------------
-- 8. Conferência do seed (vai para o log da inicialização)
-- ---------------------------------------------------------------------
SELECT 'tipo_veiculo' AS tabela, COUNT(*) AS linhas FROM tipo_veiculo
UNION ALL SELECT 'tarifa',                    COUNT(*) FROM tarifa
UNION ALL SELECT 'vaga',                      COUNT(*) FROM vaga
UNION ALL SELECT 'veiculo',                   COUNT(*) FROM veiculo
UNION ALL SELECT 'permanencia',               COUNT(*) FROM permanencia
UNION ALL SELECT 'permanencia ABERTA',        COUNT(*) FROM permanencia WHERE status = 'ABERTA'
UNION ALL SELECT 'permanencia FECHADA',       COUNT(*) FROM permanencia WHERE status = 'FECHADA'
UNION ALL SELECT 'pagamento',                 COUNT(*) FROM pagamento;

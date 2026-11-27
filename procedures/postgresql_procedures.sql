-- =====================================================================
-- Estacionamento Rotativo Central — procedures da Frente C (C1)
-- Caminho oficial de negócio: registrar_entrada e registrar_saida.
-- NENHUMA procedure faz COMMIT: quem chama controla a transação.
-- Os triggers trg_permanencia_valida / trg_permanencia_auditoria entram
-- na C2, neste mesmo arquivo, depois das procedures.
-- Fonte de verdade: decisions.md §2 (RN) e §4 (procedures/erros).
-- =====================================================================

-- ---------------------------------------------------------------------
-- registrar_entrada
-- RN-01: entrada só em vaga ativa livre; RN-06: mesmo tipo de veículo;
-- RN-07: no máximo uma permanência aberta por veículo; tarifa insert-only;
-- ESTAC-004 (tipo/tarifa), ESTAC-006 (aberta), ESTAC-001 (sem vaga).
-- O veículo é anônimo e criado na primeira entrada; seu tipo não muda.
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE registrar_entrada(p_placa VARCHAR, p_tipo VARCHAR)
LANGUAGE plpgsql
AS $$
DECLARE
    -- Tipos largos: a normalização aceita entradas quaisquer; quem valida
    -- formato é o CHECK da tabela e quem valida o tipo é o ESTAC-004 abaixo.
    v_placa             VARCHAR(50);
    v_tipo              VARCHAR(50);
    v_tipo_id           INTEGER;
    v_veiculo_tipo_id   INTEGER;
    v_veiculo_tipo_nome VARCHAR(20);
    v_tarifa_id         INTEGER;
    v_veiculo_id        INTEGER;
    v_vaga_id           INTEGER;
BEGIN
    -- Normalização: maiúsculas, sem hífen nem espaços (decisions.md §4).
    v_placa := REGEXP_REPLACE(UPPER(COALESCE(p_placa, '')), '[- ]', '', 'g');
    v_tipo  := UPPER(TRIM(COALESCE(p_tipo, '')));

    -- ESTAC-004 vem antes de ESTAC-006: valida tipo e tarifa vigente.
    SELECT tipo_id INTO v_tipo_id
    FROM tipo_veiculo
    WHERE nome = v_tipo;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ESTAC-004: tipo de veículo inválido: %', p_tipo
            USING ERRCODE = 'P0001';
    END IF;

    -- Tarifa insert-only: maior vigencia_inicio <= CURRENT_TIMESTAMP.
    SELECT tarifa_id INTO v_tarifa_id
    FROM tarifa
    WHERE tipo_id = v_tipo_id
      AND vigencia_inicio <= CURRENT_TIMESTAMP
    ORDER BY vigencia_inicio DESC
    LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ESTAC-004: sem tarifa vigente para %', v_tipo
            USING ERRCODE = 'P0001';
    END IF;

    -- Veículo criado na primeira entrada; se já existe, tipo não é alterado.
    SELECT veiculo_id, tipo_id
      INTO v_veiculo_id, v_veiculo_tipo_id
    FROM veiculo
    WHERE placa = v_placa;
    IF NOT FOUND THEN
        INSERT INTO veiculo (placa, tipo_id)
        VALUES (v_placa, v_tipo_id)
        RETURNING veiculo_id INTO v_veiculo_id;
    ELSIF v_veiculo_tipo_id <> v_tipo_id THEN
        SELECT nome INTO v_veiculo_tipo_nome
        FROM tipo_veiculo
        WHERE tipo_id = v_veiculo_tipo_id;
        RAISE EXCEPTION 'ESTAC-004: veículo % é do tipo %, não %',
            v_placa, v_veiculo_tipo_nome, v_tipo
            USING ERRCODE = 'P0001';
    END IF;

    -- ESTAC-006: uma permanência aberta por veículo (índice parcial é a 2ª camada).
    IF EXISTS (
        SELECT 1 FROM permanencia
        WHERE veiculo_id = v_veiculo_id
          AND status = 'ABERTA'
    ) THEN
        RAISE EXCEPTION 'ESTAC-006: veículo % já possui permanência aberta', v_placa
            USING ERRCODE = 'P0001';
    END IF;

    -- RN-01/RN-06: vaga ativa livre do mesmo tipo, com SKIP LOCKED para concorrência.
    SELECT va.vaga_id INTO v_vaga_id
    FROM vaga va
    WHERE va.ativa
      AND va.tipo_id = v_tipo_id
      AND NOT EXISTS (
          SELECT 1 FROM permanencia p
          WHERE p.vaga_id = va.vaga_id
            AND p.status = 'ABERTA'
      )
    ORDER BY va.codigo
    FOR UPDATE OF va SKIP LOCKED
    LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ESTAC-001: sem vaga livre do tipo %', v_tipo
            USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO permanencia (veiculo_id, vaga_id, tarifa_id, entrada, valor, status, ticket_perdido)
    VALUES (v_veiculo_id, v_vaga_id, v_tarifa_id, CURRENT_TIMESTAMP, 0, 'ABERTA', FALSE);
END;
$$;

-- ---------------------------------------------------------------------
-- registrar_saida
-- ESTAC-002: trava; inexistente ou já fechada. RN-05: ticket perdido
-- sobrepõe o tempo; RN-02: tolerância; RN-03/RN-04: 1ª hora + frações
-- iniciadas com CEIL e teto por permanência (RN-10); ESTAC-003: forma;
-- RN-08/RN-09: fecha com valor 0 ou pagamento integral, sem linha em
-- pagamento quando valor = 0. Tarifa congelada pelo tarifa_id da entrada.
-- ---------------------------------------------------------------------
CREATE OR REPLACE PROCEDURE registrar_saida(
    p_permanencia_id INTEGER,
    p_forma_pagamento VARCHAR,
    p_ticket_perdido BOOLEAN DEFAULT FALSE
)
LANGUAGE plpgsql
AS $$
DECLARE
    v_permanencia permanencia%ROWTYPE;
    v_tarifa      tarifa%ROWTYPE;
    v_minutos     NUMERIC;
    v_fracoes     NUMERIC;
    v_valor       NUMERIC(10,2);
    v_forma       VARCHAR(50);
BEGIN
    SELECT * INTO v_permanencia
    FROM permanencia
    WHERE permanencia_id = p_permanencia_id
    FOR UPDATE;
    IF NOT FOUND OR v_permanencia.status <> 'ABERTA' THEN
        RAISE EXCEPTION 'ESTAC-002: permanência inexistente ou já fechada: %', p_permanencia_id
            USING ERRCODE = 'P0001';
    END IF;

    SELECT * INTO v_tarifa
    FROM tarifa
    WHERE tarifa_id = v_permanencia.tarifa_id;

    IF COALESCE(p_ticket_perdido, FALSE) THEN
        -- RN-05: valor fixo, ignorando duração, tolerância e teto por tempo.
        v_valor := v_tarifa.ticket_perdido;
    ELSE
        v_minutos := EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - v_permanencia.entrada)) / 60;
        IF v_minutos <= v_tarifa.tolerancia_min THEN
            v_valor := 0;                                   -- RN-02
        ELSE
            -- Fração iniciada após a 1ª hora; GREATEST evita fração negativa
            -- entre a tolerância e 60 min. LEAST aplica o teto da RN-10.
            v_fracoes := GREATEST(CEIL((v_minutos - 60) / v_tarifa.fracao_min), 0);
            v_valor := LEAST(
                v_tarifa.primeira_hora + v_fracoes * v_tarifa.fracao_valor,
                v_tarifa.teto
            );
        END IF;
    END IF;

    IF v_valor > 0 THEN
        v_forma := UPPER(TRIM(COALESCE(p_forma_pagamento, '')));
        IF v_forma NOT IN ('DINHEIRO', 'PIX', 'CARTAO') THEN
            RAISE EXCEPTION 'ESTAC-003: forma de pagamento ausente/inválida: %', p_forma_pagamento
                USING ERRCODE = 'P0001';
        END IF;
        INSERT INTO pagamento (permanencia_id, valor, forma, data_hora)
        VALUES (p_permanencia_id, v_valor, v_forma, CURRENT_TIMESTAMP);
    END IF;

    -- Persiste o evento para Q08 (categoria TICKET_PERDIDO) e auditoria.
    UPDATE permanencia
    SET saida = CURRENT_TIMESTAMP,
        valor = v_valor,
        status = 'FECHADA',
        ticket_perdido = COALESCE(p_ticket_perdido, FALSE)
    WHERE permanencia_id = p_permanencia_id;
END;
$$;

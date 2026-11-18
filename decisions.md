# decisions.md — A3 Banco de Dados

**Fonte de verdade do projeto.** Última validação: 2026-11-18.
**Congelado para agentes/IA:** leia e siga; não edite este arquivo. Se uma IA/agente propuser algo que contrarie ou faltar, pare e pergunte ao grupo. Somente o grupo altera, com justificativa registrada no commit e no log de IA. Não invente regras, tabelas, colunas, consultas, números ou tempos.

O desdobramento em tarefas está em `atividades.md`; ele organiza a execução, mas não substitui as decisões deste arquivo.

## 1. Projeto e escopo

- Tema: **Estacionamento Rotativo Central** (fictício). Problema: controle manual gera saída sem cobrança, tarifa errada e nenhuma visão de ocupação/receita.
- Escopo: rotativo avulso, **sem mensalistas**. 100 vagas de carro + 20 de moto. Operação 24h. Sem reserva de vaga.
- Stack: **PostgreSQL 17**, **Java 21 (Maven/JDBC)**, **JUnit 5**, Docker Compose.
- Entrega: **3/12/2026**; apresentação de 10 min; todos os integrantes precisam de commits.
- Fora de escopo: Oracle/segundo SGBD, Python, mensalistas, reservas, código de ticket, entidade `CLIENTE`, status armazenado em `VAGA`, `CASCADE`.

## 2. Regras de negócio

| ID | Regra |
|---|---|
| RN-01 | Entrada só com vaga ativa livre do tipo do veículo. |
| RN-02 | Permanência ≤ 15 min é gratuita; 15:01 já cobra. |
| RN-03 | Carro: 1ª hora R$ 12; cada fração iniciada de 30 min após a 1ª hora R$ 4; teto R$ 45. |
| RN-04 | Moto: 1ª hora R$ 8; fração R$ 3; teto R$ 30. |
| RN-05 | Ticket perdido: R$ 60 (carro) / R$ 40 (moto), sobrepondo a tarifa por tempo. |
| RN-06 | Veículo só ocupa vaga do mesmo tipo; vaga inativa não recebe. |
| RN-07 | No máximo 1 permanência aberta por veículo e nenhuma sobreposição de permanências na mesma vaga. |
| RN-08 | Saída só fecha com valor 0 ou com pagamento integral; permanência fechada não reabre e o valor não muda. |
| RN-09 | Formas de pagamento: `DINHEIRO`, `PIX`, `CARTAO`. Valor 0 não gera linha em `PAGAMENTO`. |
| RN-10 | Operação 24h; o teto é por permanência. |

## 3. Modelo de dados

7 tabelas, nomes em português, constraints nomeadas (`pk_`, `uk_`, `fk_`, `ck_`).

| Tabela | Colunas e regras |
|---|---|
| `tipo_veiculo` | `tipo_id` PK; `nome` UK, CHECK `IN ('CARRO','MOTO')`. |
| `vaga` | `vaga_id` PK; `codigo` UK, CHECK `^[CM][0-9]{3}$`; `tipo_id` FK; `ativa` BOOLEAN. |
| `veiculo` | `veiculo_id` PK; `placa` UK, CHECK `^[A-Z]{3}[0-9][A-Z0-9][0-9]{2}$`; `tipo_id` FK. |
| `tarifa` | `tarifa_id` PK; `tipo_id` FK; `vigencia_inicio`; `tolerancia_min`; `primeira_hora`; `fracao_min`; `fracao_valor`; `teto`; `ticket_perdido`; UK `(tipo_id, vigencia_inicio)`; CHECKs de valores. |
| `permanencia` | `permanencia_id` PK; `veiculo_id`, `vaga_id`, `tarifa_id` FKs; `entrada`; `saida`; `valor`; `status`; `ticket_perdido`; CHECK `saida IS NULL OR saida > entrada`, `valor >= 0`, `status IN ('ABERTA','FECHADA')` e consistência `(ABERTA e saida NULL) ou (FECHADA e saida NOT NULL)`; índice único parcial em `(veiculo_id) WHERE status = 'ABERTA'`. |
| `pagamento` | `pagamento_id` PK; `permanencia_id` FK + UK; `valor` CHECK `> 0`; `forma` CHECK; `data_hora`. |
| `auditoria_permanencia` | `auditoria_id` PK; `permanencia_id` FK; `operacao`; `status_antes`; `status_depois`; `valor_antes`; `valor_depois`; `usuario_bd`; `data_hora`. |

- **N:M:** `veiculo` ↔ `vaga` via `permanencia` (temporal; carrega `entrada`, `saida`, `valor`, `status`). Entidade com estado: `permanencia`.
- **Cardinalidades:** `tipo_veiculo` (1,1)—(0,N) `vaga` e `veiculo`; (1,1)—(1,N) `tarifa`; `vaga`/`veiculo`/`tarifa` (1,1)—(0,N) `permanencia`; `permanencia` (1,1)—(0,1) `pagamento`; (1,1)—(1,N) `auditoria`.
- **Decisões:** ocupação derivada (sem status em `VAGA`); tarifa congelada por `tarifa_id`; veículo criado na primeira entrada; todas as FKs `ON DELETE RESTRICT`; sem ticket; auditoria por trigger.
- **Tarifa insert-only:** não há `UPDATE`/`DELETE` em `tarifa`. Mudança de preço = nova linha com nova `vigencia_inicio`; a entrada usa a maior `vigencia_inicio <= CURRENT_TIMESTAMP` para o tipo e grava `tarifa_id` na permanência.

## 4. Procedures e triggers

- `registrar_entrada(p_placa VARCHAR, p_tipo VARCHAR)`: normaliza placa/tipo (maiúsculas, remove hífen/espaços); valida tipo/veículo (`ESTAC-004` antes de `ESTAC-006`); impede permanência aberta do mesmo veículo; busca a tarifa de maior `vigencia_inicio <= CURRENT_TIMESTAMP` (insert-only); escolhe vaga ativa livre do tipo com `FOR UPDATE SKIP LOCKED`; insere permanência aberta; o trigger audita. **Sem COMMIT.**
- `registrar_saida(p_permanencia_id INTEGER, p_forma_pagamento VARCHAR, p_ticket_perdido BOOLEAN DEFAULT FALSE)`: trava a permanência; se não estiver aberta → `ESTAC-002`; calcula ticket perdido/tolerância/1ª hora/frações com `CEIL`/teto usando a tarifa congelada; se valor > 0 normaliza e valida a forma (`ESTAC-003`) e insere pagamento; fecha a permanência; o trigger audita. **Sem COMMIT.**
- `trg_permanencia_valida` (BEFORE INSERT OR UPDATE): tipo do veículo = tipo da vaga (`ESTAC-004`), sem sobreposição na vaga (`ESTAC-005`), sem segunda permanência aberta do veículo (`ESTAC-006`). Vale para INSERT direto.
- `trg_permanencia_auditoria` (AFTER INSERT OR UPDATE OF status, valor): grava antes/depois em `auditoria_permanencia`.
- Índice único parcial de permanência aberta por veículo é a segunda camada da RN-07.
- **Caminho oficial:** as procedures. `INSERT`/`UPDATE` direto não é caminho de negócio; os triggers garantem tipo, sobreposição e uma permanência aberta. Vaga ativa, fechamento com valor 0/pagamento integral e imutabilidade da permanência fechada são responsabilidade de `registrar_entrada`/`registrar_saida`.
- Nos triggers de `UPDATE`, a própria permanência (`NEW.permanencia_id`) não conta na sobreposição nem na checagem de segunda aberta.

| Código | Uso |
|---|---|
| `ESTAC-001` | Sem vaga livre do tipo. |
| `ESTAC-002` | Permanência inexistente ou já fechada. |
| `ESTAC-003` | Forma de pagamento ausente/inválida. |
| `ESTAC-004` | Tipo inválido/incompatível ou sem tarifa. |
| `ESTAC-005` | Sobreposição de permanência na vaga. |
| `ESTAC-006` | Veículo já possui permanência aberta. |

Erros com SQLSTATE `P0001` e prefixo `ESTAC-00x` na mensagem; o app traduz pelo prefixo. Concorrência: duas sessões para a última vaga; só uma vence (`SKIP LOCKED` + índice único + teste).

## 5. Consultas (10)

Todas em `postgresql/03_consultas.sql`, com marcador `-- @Qnn` e `;` ao final.

| # | Objetivo | Técnica |
|---|---|---|
| Q01 | Ocupação atual por tipo (total, ocupadas, livres, %) | JOIN + LEFT JOIN + GROUP BY |
| Q02 | Receita diária dos últimos 30 dias + acumulado | CTE + `SUM() OVER` |
| Q03 | Top 10 veículos por gasto | JOINs + GROUP BY + `RANK()` (inclui empate) |
| Q04 | Tempo médio/mín/máx por tipo | JOIN + AVG/MIN/MAX |
| Q05 | Movimento por hora (entradas e saídas) | UNION ALL + GROUP BY |
| Q06 | Receita por forma de pagamento + % | GROUP BY + `SUM(SUM()) OVER ()` |
| Q07 | Vagas nunca utilizadas | `NOT EXISTS` + JOIN |
| Q08 | Tolerância e ticket perdido | CASE + GROUP BY |
| Q09 | Receita mensal + variação vs mês anterior | CTE + `LAG() OVER` |
| Q10 | Receita por tipo e forma com subtotais | JOIN + `GROUP BY ROLLUP` |

Fronteiras obrigatórias no seed: 15:00/15:01, 60:00/60:01, 90:00/90:01, 5h00:00/5h00:01, ticket perdido, empate no top 10, vagas nunca usadas, três formas de pagamento, dois meses e 10 carros + 4 motos com permanência aberta. Datas relativas a `CURRENT_DATE`/`CURRENT_TIMESTAMP`; permanências fechadas usam horários fixos (ex.: `CURRENT_DATE - 1 + TIME '08:00'`), e a aberta de 10h usa `CURRENT_TIMESTAMP - INTERVAL '10 hours'`. Resultados esperados calculados à mão e verificados no JUnit.

**Critérios de resultado (seed e JUnit):**

- **Q01:** vagas ativas (`ativa = TRUE`) por tipo; ocupada = permanência `ABERTA` na vaga; livre = total - ocupadas; % = ocupadas / total (0 se total = 0).
- **Q02:** pagamentos com `data_hora >= CURRENT_DATE - INTERVAL '29 days'` e `< CURRENT_DATE + INTERVAL '1 day'`; agrupar por dia; receita = `SUM(pagamento.valor)`; acumulado por dia; dias sem pagamento não aparecem.
- **Q03:** gasto = `SUM(pagamento.valor)` por veículo, somente veículos com ao menos um pagamento; `RANK() OVER (ORDER BY gasto DESC)` e filtro `posicao <= 10`; empate pode devolver mais de 10 linhas.
- **Q04:** somente permanências `FECHADA`; duração em minutos = `EXTRACT(EPOCH FROM (saida - entrada)) / 60`; média, mínimo e máximo por tipo.
- **Q05:** hora do dia (`EXTRACT(HOUR ...)`, 0–23); entradas por `entrada`, saídas por `saida` não nula; `UNION ALL` + agregação; horas sem movimento podem ser omitidas. O teste forte é a soma de entradas/saídas; a hora da permanência aberta de 10h não é fixa.
- **Q06:** pagamentos por forma; receita e percentual = `100 * valor / total`; apenas formas com pagamento; total via `SUM(SUM(valor)) OVER ()`.
- **Q07:** vagas ativas sem nenhuma permanência (qualquer status); usar `NOT EXISTS`.
- **Q08:** somente permanências `FECHADA`; categoria `TICKET_PERDIDO` se `ticket_perdido`, senão `TOLERANCIA` se duração <= `tarifa.tolerancia_min`, senão `COBRADO`; contar e somar `valor` por categoria.
- **Q09:** pagamentos agrupados por mês (`DATE_TRUNC('month', data_hora)`); receita; `LAG` para variação percentual vs mês anterior; primeiro mês com anterior `NULL`; apenas meses com pagamento.
- **Q10:** pagamentos por tipo e forma; `GROUP BY ROLLUP(tipo, forma)`; `tipo NULL` e `forma NULL` = total geral; `forma NULL` com tipo = subtotal do tipo; somar `valor`.

## 6. App Java e testes

- `app/` Maven, Java 21; dependências `org.postgresql:postgresql` e JUnit 5; fat JAR.
- `App.java`: `autoCommit=false`; consulta com `PreparedStatement` e bind; procedures com `CallableStatement` e `escapeSyntaxCallMode=callIfNoReturn`; `Savepoint` antes de cada chamada; `BigDecimal` para dinheiro; erro de negócio traduzido por `ESTAC-00x`.
- Demo: consulta vagas livres; entrada feliz; consulta permanência aberta; saída da permanência semeada há 10h (teto R$ 45); saída repetida → `ESTAC-002`; tipo incompatível → `ESTAC-004`; `ROLLBACK` por padrão, `--efetivar` faz `COMMIT`.
- Testes JUnit em `app/src/test/java`; serviço `testes` roda `mvn test`. Cerca de 25 testes: 4 DDL/constraints, 7 procedures, 3 triggers, 10 consultas e 1 concorrência (mínimo da rubrica: 10). Cada teste roda em transação com rollback; concorrência usa duas conexões.
- Testes de consulta leem `postgresql/03_consultas.sql` e separam por `@Qnn`.

## 7. Estrutura e entrega

```text
.
├── README.md
├── AGENTS.md
├── opencode.json
├── decisions.md
├── atividades.md
├── docker-compose.yml
├── der/estacionamento.puml
├── postgresql/{01_ddl,02_inserts,03_consultas,04_indices}.sql
├── procedures/postgresql_procedures.sql
├── performance/{medir.sql,explain_antes_depois.txt}
├── app/{pom.xml,src/main/java,src/test/java}
└── docker/{postgres-init.sh,Dockerfile.app,Dockerfile.testes}
```

- Compose: `postgres` (postgres:17, healthcheck), `testes` (profile `testes`, Maven) e `app` (profile `apps`, JRE). Init aborta no primeiro erro. Credenciais de laboratório: banco/usuário `estacionamento`, senha `estacionamento_pwd`; nunca usar senha real.
- Comandos: `docker compose up -d --wait`, `docker compose run --rm testes`, `docker compose --profile apps run --rm app`, `docker compose down -v`.
- README com 7 blocos: domínio, DER, decisões, como rodar, performance antes/depois, dificuldades/aprendizados e Uso de IA (≥5 registros, ≥4 tipos de artefato, ≥2 erros reais).
- `AGENTS.md` e `opencode.json` versionados; push negado ao agente.
- Entrega: tag `v1.0.0` no commit entregue, zip com `.git`, capa PDF com nomes/RAs/link/tag/hash. Commits em Conventional Commits, de todos os integrantes.

## 8. Frentes de trabalho

O grupo distribui as pessoas; a ordem de dependência é **A → (B ∥ C) → testes → documentação**.

- **A — Modelagem e DDL:** DER, `01_ddl.sql`, `02_inserts.sql`, normalização, decisões no README, testes de constraints.
- **B — Consultas e desempenho:** `03_consultas.sql`, resultados esperados, `04_indices.sql`, massa de dados, `EXPLAIN ANALYZE` antes/depois, testes de consulta.
- **C — Lógica e aplicação:** procedures/triggers, app Java, JUnit, compose, README final e log de IA.
- **Todos:** revisão cruzada, commits e apresentação.

## 9. Regras para agentes e IAs

- Leia este arquivo antes de codar; não o contrarie e **não o edite**. Faltou uma decisão? Pergunte ao grupo; somente o grupo altera, com registro.
- Só dialeto PostgreSQL: `VARCHAR`, `INTEGER`, `NUMERIC(10,2)`, `TIMESTAMP`, `DATE`, `BOOLEAN`, `INTEGER GENERATED BY DEFAULT AS IDENTITY` para PKs e `ON DELETE RESTRICT` nas FKs. Nunca sintaxe Oracle.
- Procedures **nunca** fazem `COMMIT`; quem chama controla a transação.
- Todo SQL só vale depois de executado no banco de laboratório; registre a saída.
- Nunca `DROP`, `TRUNCATE` ou `DELETE`/`UPDATE` sem `WHERE`; nunca contra banco que não seja o do laboratório.
- Sem dados pessoais reais, senhas reais ou chaves no repositório; dados fictícios.
- Na aplicação, só bind; nunca concatenar valores.
- Não invente tempos de performance; cole o `EXPLAIN` real.
- Não crie tabela, coluna, consulta ou código de erro fora deste arquivo. Se faltar, pare e peça ao grupo; somente o grupo atualiza este arquivo.
- Não faça push; o grupo publica. Registre no log de IA o pedido, o erro/omissão do agente e a correção do grupo.
- **Congelado para agentes/IA:** não use `edit`, `write`, `patch` nem comandos de shell para alterar `decisions.md`; o grupo é o único dono de mudanças.

## 10. Congelamento

Este arquivo é somente leitura para agentes/IA. O grupo pode corrigi-lo, mas a mudança deve ser justificada no commit e registrada no log de IA. Nenhum agente deve editar `decisions.md` por `edit`, `write`, `patch` ou shell.

# Estacionamento Rotativo Central

A3 — Projeto Integrador de Banco de Dados (2026/2).

Sistema de banco de dados para um estacionamento rotativo fictício com **100 vagas de carro e 20 de moto**. O problema: o controle manual gera saída sem cobrança, tarifa calculada errado, ticket perdido sem registro e nenhuma visão de ocupação/receita. O banco resolve isso com tarifa por tempo, tolerância, controle de lotação, pagamento e auditoria.

**Stack:** PostgreSQL 17, Java 21 (Maven/JDBC), JUnit 5 e Docker Compose.

> Este README é um esqueleto da Frente 0. O README completo, com DER, decisões, performance e Uso de IA, será fechado na Frente D.

## Estado atual

- Estrutura de diretórios, `.gitignore`, `AGENTS.md`, `opencode.json` e `docker-compose.yml` criados.
- `decisions.md` validado e congelado para agentes/IA; o grupo só altera com justificativa registrada.
- `docker/postgres-init.sh` executa os scripts na primeira subida do volume.
- **`postgresql/01_ddl.sql` já contém as 7 tabelas e constraints (A2) e `postgresql/02_inserts.sql` já contém o seed com as fronteiras (A3).** `03_consultas`, `04_indices` e `procedures/` ainda são placeholders.
- Subida limpa do compose validada pelo grupo (`down -v` + `up -d --wait` sem erro, healthcheck `healthy`).
- Serviços `testes` e `app` entram na Frente C.

## Como rodar

Pré-requisito: Docker com Compose.

```bash
docker compose down -v      # apaga o volume e força a reinicialização
docker compose up -d --wait # sobe o PostgreSQL e roda o init
```

Conferir o banco:

```bash
docker compose exec postgres psql -U estacionamento -d estacionamento
```

Derrubar tudo:

```bash
docker compose down -v
```

### Credenciais de laboratório

- Banco: `estacionamento`
- Usuário: `estacionamento`
- Senha: `estacionamento_pwd`
- Porta: `${PG_PORT:-5432}` (use `PG_PORT=5433` se a 5432 estiver ocupada)

São credenciais de laboratório, não de produção.

## Regras de negócio

Regras fechadas no [`decisions.md`](decisions.md) §2 — este bloco é uma cópia para leitura; em caso de divergência, o `decisions.md` vence.

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

Onde cada regra é imposta: RN-01, RN-06 e RN-07 pela procedure `registrar_entrada` e pelos triggers; RN-02 a RN-05, RN-08 e RN-09 pela `registrar_saida`; RN-10 pelo cálculo de tempo (Frentes C e B).

## Dados de exemplo (A3)

Seed em [`postgresql/02_inserts.sql`](postgresql/02_inserts.sql), executado pelo init logo após o DDL. Contagens impressas na subida limpa (`docker compose down -v && docker compose up -d --wait`, init sem erro):

| Tabela / recorte | Linhas |
|---|---:|
| `tipo_veiculo` | 2 |
| `tarifa` | 2 |
| `vaga` | 120 |
| `veiculo` | 31 |
| `permanencia` | 31 |
| `permanencia` ABERTA | 14 |
| `permanencia` FECHADA | 17 |
| `pagamento` | 15 |

Fronteiras cobradas (duração exata calculada no banco, valor gravado no seed):

| Duração | Carro | Moto |
|---|---:|---:|
| 15:00 | R$ 0,00 (tolerância) | R$ 0,00 (tolerância) |
| 15:01 | R$ 12,00 | R$ 8,00 |
| 60:00 | R$ 12,00 | R$ 8,00 |
| 60:01 | R$ 16,00 | R$ 11,00 |
| 90:00 | R$ 16,00 | R$ 11,00 |
| 90:01 | R$ 20,00 | R$ 14,00 |
| 5h00:00 | R$ 44,00 | R$ 30,00 (teto) |
| 5h00:01 | R$ 45,00 (teto) | — |
| ticket perdido (45:00) | R$ 60,00 | R$ 40,00 |

Verificação no PostgreSQL 17: **0** divergências entre o valor semeado e o valor recalculado pela tarifa congelada nas 17 permanências fechadas; **0** pagamentos com valor diferente da permanência ou gerados para valor 0; **0** permanências com tipo de veículo diferente do tipo da vaga, **0** sobreposições na mesma vaga e **0** veículos com duas permanências abertas. Q01 devolve CARRO 100/10 ocupadas e MOTO 20/4 ocupadas. Q03 tem empate na posição 10 (duas placas com R$ 12,00), por isso `RANK()` devolve 11 linhas com `posicao <= 10`. `C100` e `M020` aparecem como vagas nunca utilizadas. Pagamentos: `DINHEIRO` 5 / R$ 110,00, `PIX` 6 / R$ 136,00, `CARTAO` 4 / R$ 101,00, em dois meses (outubro R$ 115,00 e novembro R$ 232,00 na subida de 24/11/2026). As datas são relativas a `CURRENT_DATE`/`CURRENT_TIMESTAMP`, portanto os totais por mês acompanham a data da subida.

## Subida limpa (A4)

`docker compose down -v && docker compose up -d --wait` terminou com `EXIT_COPOSE=0`, container `Up (healthy)` e log do init sem `ERROR`:

```text
[init] executando /scripts/postgresql/01_ddl.sql
[init] executando /scripts/postgresql/02_inserts.sql
[init] executando /scripts/procedures/postgresql_procedures.sql
[init] executando /scripts/postgresql/04_indices.sql
[init] concluído.
```

Conferência no `psql` (`PostgreSQL 17.12`): **7** tabelas no schema `public`; **7** constraints `pk_`, **5** `uk_`, **8** `fk_` e **15** `ck_`, nenhuma fora do padrão de nome; as 8 FKs têm `confdeltype = r` (`ON DELETE RESTRICT`); índice `uk_permanencia_veiculo_aberta` definido como `CREATE UNIQUE INDEX ... ON public.permanencia USING btree (veiculo_id) WHERE ((status)::text = 'ABERTA'::text)`. Contagens: `tipo_veiculo=2`, `vaga=120`, `veiculo=31`, `tarifa=2`, `permanencia=31`, `pagamento=15`, `auditoria_permanencia=0`. `SELECT`s de conferência mostraram as tarifas (carro 15/12/30/4/45/60 e moto 15/8/30/3/30/40), as permanências `C001` (R$ 0,00), `C002` (R$ 12,00), `C009` (ticket perdido, R$ 60,00), `C010` (ABERTA, entrada há 10 horas) e `M008` (ticket perdido, R$ 40,00), além de 10 vagas de carro ocupadas.

Aborto do init confirmado nas duas pontas: injetando `SELECT 1/0;` no fim de `01_ddl.sql` e, em outra subida, no fim de `04_indices.sql`, `docker compose up -d --wait` devolveu `EXIT_COPOSE=1`, o container ficou `Exited (3)`, o log mostrou `ERROR: division by zero` e **não** mostrou `[init] concluído.`. Os arquivos foram restaurados e a subida limpa foi repetida com sucesso (estado final `healthy`).

## DER e Decisões de Modelagem

![DER do Estacionamento Rotativo Central](der/estacionamento.png)

Arquivo-fonte: [`der/estacionamento.puml`](der/estacionamento.puml).

### Cardinalidades (mín., máx.)

| Relação | Card. | Leitura |
|---|---|---|
| `tipo_veiculo` → `vaga` | (1,1) — (0,N) | Um tipo tem 0..N vagas; toda vaga tem exatamente 1 tipo. |
| `tipo_veiculo` → `veiculo` | (1,1) — (0,N) | Um tipo tem 0..N veículos; todo veículo tem exatamente 1 tipo. |
| `tipo_veiculo` → `tarifa` | (1,1) — (1,N) | Um tipo tem 1..N tarifas (insert-only, uma por vigência); toda tarifa tem 1 tipo. |
| `veiculo` → `permanencia` | (1,1) — (0,N) | Um veículo tem 0..N permanências; toda permanência tem 1 veículo. |
| `vaga` → `permanencia` | (1,1) — (0,N) | Uma vaga tem 0..N permanências; toda permanência tem 1 vaga. |
| `tarifa` → `permanencia` | (1,1) — (0,N) | Uma tarifa congela 0..N permanências; toda permanência referencia 1 tarifa. |
| `permanencia` → `pagamento` | (1,1) — (0,1) | Uma permanência tem 0..1 pagamento (valor 0 não gera linha); todo pagamento tem 1 permanência. |
| `permanencia` → `auditoria_permanencia` | (1,1) — (1,N) | Toda permanência gera 1..N registros de auditoria; todo registro pertence a 1 permanência. |

`veiculo` ↔ `vaga` é **N:M** resolvida por `permanencia` — a associativa carrega `entrada`, `saida`, `valor` e `status` (entidade **com estado**).

### Decisões de modelagem

| Decisão | Por quê |
|---|---|
| **`PERMANENCIA` é entidade, não atributo** | Ela carrega estado próprio (`entrada`, `saida`, `valor`, `status`, `ticket_perdido`) e é a associativa **com estado** da N:M. Uma vaga pode ter muitas permanências no tempo e um veículo também; nenhum dos dois "guarda" a outra. |
| **Ocupação é derivada (sem status em `VAGA`)** | Status armazenado em `VAGA` fica desatualizado e permite duas fontes de verdade. A ocupação atual é `COUNT` de permanências `ABERTA` por vaga — Q01. |
| **Tarifa congelada por `tarifa_id`** | A permanência referencia a tarifa vigente na **entrada**. Como `tarifa` é insert-only (preço novo = linha nova), recalcular depois não muda o histórico. |
| **Todas as FKs `ON DELETE RESTRICT`** | Impede apagar tipo, vaga, veículo ou tarifa com dados dependentes. Nada de `CASCADE`: apagar histórico silenciosamente quebraria auditoria e receita. |
| **Sem entidade `CLIENTE`** | Escopo é rotativo avulso, sem mensalistas e sem reserva. Não há dado de pessoa a guardar; `veiculo` é anônimo, identificado só pela placa. |
| **Sem código de ticket** | O ticket não é entidade: `ticket_perdido` é um booleano em `PERMANENCIA` que dispara tarifa fixa (RN-05). |
| **`auditoria_permanencia` não tem `UK`** | É log de eventos: a mesma permanência pode gerar várias linhas (INSERT + updates de `status`/`valor`). |

## Estrutura

```text
.
├── README.md
├── AGENTS.md
├── config.md           # requisitos do computador para rodar o projeto
├── opencode.json
├── decisions.md          # fonte de verdade (congelado para agentes/IA)
├── atividades.md         # tarefas por frente
├── docker-compose.yml
├── der/                  # DER em PlantUML (Frente A)
├── postgresql/           # 01_ddl, 02_inserts, 03_consultas, 04_indices
├── procedures/           # procedures e triggers (Frente C)
├── performance/          # massa de dados e EXPLAIN antes/depois (Frente B)
├── app/                  # aplicação Java/Maven e testes (Frente C)
└── docker/               # init do PostgreSQL e Dockerfiles
```

## Documentos de trabalho

- [`config.md`](config.md) — o que precisa estar instalado no computador para rodar o projeto (Docker, Git, portas, recursos).
- [`decisions.md`](decisions.md) — decisões fechadas; somente leitura para agentes/IA. O grupo só altera com justificativa registrada no commit e no log de IA.
- [`atividades.md`](atividades.md) — tarefas, dependências, definição de pronto e matriz da rubrica.

## Próximos passos

1. Frente A: DER, DDL, seed e subida limpa.
2. Frente B: 10 consultas, índices e `EXPLAIN` antes/depois.
3. Frente C: procedures/triggers, app Java e suíte JUnit.
4. Frente D: README final, log de IA, tag, zip e apresentação.

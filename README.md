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

# config.md — O que precisa estar instalado no computador

**Objetivo:** qualquer integrante (ou o professor) clona o repositório e roda o projeto do zero, sem depender de configuração escondida ou de máquina de alguém.

Stack do projeto: **PostgreSQL 17**, **Java 21 (Maven/JDBC)**, **JUnit 5**, **Docker Compose**. Tudo roda em container: o computador só precisa de **Docker** e **Git**.

## 1. Obrigatório (mínimo para rodar)

| Ferramenta | Versão mínima | Para que serve | Como verificar |
|---|---|---|---|
| Docker Engine ou Docker Desktop | Compose **v2** (`docker compose`) | Sobe o PostgreSQL, roda o init, os testes e a app | `docker compose version` |
| Daemon Docker em execução | — | Sem ele nada sobe | `docker info` |
| Git | 2.30+ | Clonar e commitar | `git --version` |
| Porta 5432 livre | — | Expõe o PostgreSQL | conflito no `up` → use `PG_PORT=5433` |

- **Windows:** Docker Desktop integrado ao **WSL2** (o projeto usa caminhos Unix).
- **Linux:** o usuário precisa estar no grupo `docker` (`sudo usermod -aG docker $USER`, depois relogar) ou rodar com `sudo`.
- **macOS:** Docker Desktop basta; a porta 5432 também pode estar ocupada pelo PostgreSQL local.

## 2. Verificação rápida

```bash
docker --version
docker compose version
docker info
git --version
```

Saída esperada: versões impressas e `Server Version: ...` no `docker info`. Se algum comando falhar, instale/ative antes de continuar.

## 3. Opcional (desenvolvimento fora do container)

| Ferramenta | Versão | Quando é útil |
|---|---|---|
| JDK | 21 | Rodar/testar a app localmente sem Docker (Frente C) |
| Maven | 3.9+ | Mesmo caso; no projeto oficial roda no container `testes` |
| Cliente `psql` | 17 | Conferir o banco sem `docker compose exec` |
| Editor/IDE | — | VS Code, IntelliJ, Eclipse |
| PlantUML | — | Renderizar `der/estacionamento.puml` (Frente A) |
| opencode | — | Agentes/IA; já configurado em `opencode.json` |

Esses itens **não são obrigatórios**: a suíte JUnit roda em `docker compose run --rm testes` e a app em `docker compose --profile apps run --rm app`.

## 4. Recursos em disco

| Item | Tamanho aproximado |
|---|---|
| Imagem `postgres:17` | ~150 MB |
| Imagens Maven/JRE 21 (Frente C) | ~600 MB |
| Volume do banco com seed | ~50 MB |
| **Total estimado** | **~1 GB livres** |

## 5. O que NÃO é preciso / não é permitido

- **Não** instale Oracle, MySQL ou outro SGBD — o projeto é **PostgreSQL 17 só**.
- **Não** é preciso PostgreSQL local: ele roda no container `postgres`.
- **Não** instale Python para rodar o projeto (fora de escopo).
- **Não** coloque senhas, chaves ou dados pessoais reais no repositório. As credenciais são só de laboratório (ver [`README.md`](README.md#credenciais-de-laboratório)).
- **Não** é preciso internet depois de baixadas as imagens.

## 6. Estado por frente

| Frente | Exige |
|---|---|
| 0 (fim) | Docker + Git |
| A (DER/DDL/seed) | Docker + Git |
| B (consultas/índices) | Docker + Git |
| C (procedures/app/testes) | Docker + Git (JDK/Maven só se rodar fora do container) |
| D (docs/entrega) | Git + editor |

## 7. Checagem final de entrega

```bash
docker compose down -v
docker compose up -d --wait
docker compose run --rm testes
docker compose --profile apps run --rm app
```

Se esses comandos rodam em uma máquina limpa, o ambiente está correto.

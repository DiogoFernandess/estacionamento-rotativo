#!/usr/bin/env bash
# Executado pelo entrypoint do PostgreSQL na primeira inicialização do volume.
# A ordem importa: DDL -> dados -> procedures/triggers -> índices.
# Qualquer erro aborta a inicialização (ON_ERROR_STOP + set -e).
set -euo pipefail

rodar_sql() {
  local arquivo="$1"
  echo "[init] executando ${arquivo}"
  psql \
    -X \
    -v ON_ERROR_STOP=1 \
    --username "${POSTGRES_USER}" \
    --dbname "${POSTGRES_DB}" \
    --file "${arquivo}"
}

rodar_sql /scripts/postgresql/01_ddl.sql
rodar_sql /scripts/postgresql/02_inserts.sql
rodar_sql /scripts/procedures/postgresql_procedures.sql
rodar_sql /scripts/postgresql/04_indices.sql

echo "[init] concluído."

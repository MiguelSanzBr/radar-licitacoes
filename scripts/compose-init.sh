#!/usr/bin/env bash
# Cria o que o compose precisa e o repositorio nao versiona: infra/compose/.env (copia do exemplo)
# e infra/compose/secrets/{db_password,read_writer_password} (valores aleatorios). Nunca sobrescreve o que ja existe.
# Atencao: o Postgres so aplica a senha do banco, e a migration V6 so a do radar_read_writer, na primeira
# criacao do volume. Apagar secrets/ sem `CLEAN=1 make down` deixa o banco com a senha antiga.
set -euo pipefail

dir="$(cd "$(dirname "$0")/../infra/compose" && pwd)"

[ -f "$dir/.env" ] || cp "$dir/.env.example" "$dir/.env"

mkdir -p "$dir/secrets"
chmod 700 "$dir/secrets"
for name in db_password read_writer_password; do
  if [ ! -f "$dir/secrets/$name" ]; then
    head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' >"$dir/secrets/$name"
    # o container da aplicacao roda como uid 10001 e le o arquivo montado: o diretorio 0700 protege
    chmod 644 "$dir/secrets/$name"
  fi
done

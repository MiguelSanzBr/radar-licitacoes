#!/usr/bin/env bash
# Imprime o tamanho em MB (10^6 bytes) da imagem radar-licitacoes:local, construindo-a se faltar.
# Uso: scripts/image-size.sh [--build]
set -euo pipefail

image="${IMAGE:-radar-licitacoes:local}"
cd "$(dirname "$0")/.."

if [ "${1:-}" = "--build" ] || ! docker image inspect "$image" >/dev/null 2>&1; then
  docker build -t "$image" . >&2
fi

bytes="$(docker image inspect --format '{{.Size}}' "$image")"
echo "$((bytes / 1000000)) MB"

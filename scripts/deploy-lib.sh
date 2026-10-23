#!/usr/bin/env bash
# Funcoes de deploy.sh e rollback.sh. Carregue com `. scripts/deploy-lib.sh`; nao roda sozinho.
# DOCKER, STATE_DIR e COMPOSE_FILE trocam os padroes (os testes usam um docker simulado).
# shellcheck disable=SC2034  # as variaveis sao lidas por quem carrega o arquivo
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DOCKER=${DOCKER:-docker}
COMPOSE_FILE=${COMPOSE_FILE:-$ROOT/infra/compose/docker-compose.yml}
STATE_DIR=${STATE_DIR:-$ROOT/infra/compose}
DEPLOYED="$STATE_DIR/.deployed"
PREVIOUS="$STATE_DIR/.deployed.previous"

# Digest da imagem: o repositorio de onde veio (pull do registry) ou o id local (build).
image_digest() { # imagem
  "$DOCKER" image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}{{.Id}}{{end}}' "$1"
}

# Campo de um arquivo de estado (linhas CHAVE=valor): field ARQUIVO CHAVE.
field() {
  sed -n "s/^$2=//p" "$1"
}

# Grava o estado de um deploy: tag e digest da imagem no ar. Escreve ao lado e troca de uma vez,
# para o rollback nunca ler um registro pela metade.
record() { # arquivo tag digest
  local tmp
  tmp=$(mktemp "$STATE_DIR/.record.XXXXXX")
  printf 'tag=%s\ndigest=%s\n' "$2" "$3" >"$tmp"
  mv -f "$tmp" "$1"
}

# Sobe o servico app com a tag e espera o healthcheck (readiness) do compose.
compose_up() { # tag
  RADAR_TAG="$1" "$DOCKER" compose -f "$COMPOSE_FILE" up -d --wait app
}

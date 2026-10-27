#!/usr/bin/env bash
# Implanta a imagem TAG no compose e confere o smoke. Se o compose ou o smoke falharem, volta ao
# deploy anterior (rollback.sh) e sai com erro. Uso: scripts/deploy.sh TAG
# Com RADAR_REGISTRY (CI: ghcr.io/<dono>/radar-licitacoes) puxa essa imagem e a grava como
# radar-licitacoes:TAG, o nome que o compose usa.
set -euo pipefail

# shellcheck source=scripts/deploy-lib.sh
. "$(dirname "$0")/deploy-lib.sh"
tag="${1:?uso: $0 TAG}"
image="radar-licitacoes:$tag"

if [ -n "${RADAR_REGISTRY:-}" ]; then
  "$DOCKER" pull "$RADAR_REGISTRY:$tag"
  "$DOCKER" tag "$RADAR_REGISTRY:$tag" "$image"
fi

if ! digest=$(image_digest "$image" 2>/dev/null); then
  echo "deploy: imagem $image ausente (rode make image RADAR_TAG=$tag)" >&2
  exit 1
fi

# O deploy anterior so muda quando a imagem muda: redeployar a mesma nao apaga o que o rollback usaria.
if [ ! -f "$DEPLOYED" ]; then
  rm -f "$PREVIOUS"
elif [ "$(field "$DEPLOYED" digest)" != "$digest" ]; then
  cp "$DEPLOYED" "$PREVIOUS"
fi
# Sem deploy anterior, uma falha aqui nao tem para onde voltar: avisa antes de tentar.
[ -f "$PREVIOUS" ] || echo "deploy: sem deploy anterior; se $tag falhar, nao ha para onde voltar" >&2

if compose_up "$tag" && "$ROOT/scripts/smoke.sh"; then
  record "$DEPLOYED" "$tag" "$digest"
  echo "deploy: $tag no ar ($digest)"
  exit 0
fi

echo "deploy: $tag falhou" >&2
# O log do app vai junto: o rollback recria o container e apaga o motivo da falha.
"$DOCKER" compose -f "$COMPOSE_FILE" logs --no-color --tail 200 app >&2 || true
if [ -f "$PREVIOUS" ]; then
  echo "deploy: voltando ao deploy anterior" >&2
  "$ROOT/scripts/rollback.sh"
fi
exit 1

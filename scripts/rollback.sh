#!/usr/bin/env bash
# Volta ao deploy anterior registrado em infra/compose/.deployed.previous, pelo digest da imagem.
# Sem deploy anterior, falha com mensagem. Uso: scripts/rollback.sh
set -euo pipefail

# shellcheck source=scripts/deploy-lib.sh
. "$(dirname "$0")/deploy-lib.sh"

if [ ! -f "$PREVIOUS" ]; then
  echo "rollback: sem deploy anterior registrado ($PREVIOUS)" >&2
  exit 1
fi
tag=$(field "$PREVIOUS" tag)
digest=$(field "$PREVIOUS" digest)
: "${digest:?rollback: $PREVIOUS sem digest}"

# O digest e a referencia, nao a tag: a tag pode ter sido movida desde o deploy anterior.
"$DOCKER" tag "$digest" radar-licitacoes:rollback
if ! compose_up rollback || ! "$ROOT/scripts/smoke.sh"; then
  echo "rollback: $tag nao subiu limpo; veja make logs SERVICE=app" >&2
  exit 1
fi
record "$DEPLOYED" "$tag" "$digest"
rm -f "$PREVIOUS"
echo "rollback: voltou para $tag ($digest)"

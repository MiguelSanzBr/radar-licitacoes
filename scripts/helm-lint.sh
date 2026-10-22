#!/usr/bin/env bash
# helm lint e kubeconform -strict sobre o resultado de helm template, com os valores padrão e com
# todos os opcionais ligados (HPA, PDB, Secret criado pelo chart).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CHART="$ROOT/infra/helm/radar"
K8S_VERSION=${K8S_VERSION:-1.37.0}

"$ROOT/scripts/helm-tools.sh"
export PATH="${HELM_TOOLS_DIR:-$HOME/.local/bin}:$PATH"

check() { # nome, args do helm...
  local name=$1; shift
  echo "== $name"
  helm lint "$CHART" "$@"
  helm template radar "$CHART" "$@" | kubeconform -strict -summary -kubernetes-version "$K8S_VERSION"
}

check defaults --set secret.existingName=radar-db
check all-optionals --set autoscaling.enabled=true --set autoscaling.minReplicas=2 --set secret.create=true \
  --set secret.values.SPRING_DATASOURCE_PASSWORD=x

# Sem Secret criado nem existente o chart tem de se recusar a renderizar.
echo "== sem secret"
if helm template radar "$CHART" >/dev/null 2>&1; then echo "chart renderizou sem secret" >&2; exit 1; fi

# preStop + fase de desligamento maiores que o grace period tambem devem barrar o render.
echo "== grace period curto"
if helm template radar "$CHART" --set secret.existingName=x --set terminationGracePeriodSeconds=25 >/dev/null 2>&1; then
  echo "chart renderizou com grace period menor que preStop + desligamento" >&2; exit 1
fi

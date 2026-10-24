#!/usr/bin/env bash
# Smoke em kind: chart instalado com as probes reais, Pod Ready e readiness respondendo.
# Pré-requisito: imagem radar-licitacoes:local (make image); SMOKE_IMAGE_TAG troca a tag. Apaga o cluster ao sair.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
CLUSTER=radar-smoke
NODE_IMAGE=kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5
IMAGE_TAG=${SMOKE_IMAGE_TAG:-local}
IMAGE=radar-licitacoes:$IMAGE_TAG
PORT=${SMOKE_PORT:-18080}

"$ROOT/scripts/helm-tools.sh"
export PATH="${HELM_TOOLS_DIR:-$HOME/.local/bin}:$PATH"

docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "falta $IMAGE: rode make image" >&2; exit 1; }

RELEASE=radar
CTX=kind-$CLUSTER
LABEL="app.kubernetes.io/instance=$RELEASE"
LOGS=$(mktemp)
k() { kubectl --context "$CTX" "$@"; }

PF_PID=
LOGS_PID=
cleanup() {
  status=$?
  for pid in "$PF_PID" "$LOGS_PID"; do
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; fi
  done
  if [ "$status" -ne 0 ]; then
    k get pods -o wide || true
    k logs -l "$LABEL" --tail=80 || true
  fi
  rm -f "$LOGS"
  helm --kube-context "$CTX" uninstall "$RELEASE" >/dev/null 2>&1 || true
  kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
kind create cluster --name "$CLUSTER" --image "$NODE_IMAGE" --wait 120s
kind load docker-image "$IMAGE" --name "$CLUSTER"

k apply -f "$ROOT/infra/helm/smoke/postgres.yaml"
k rollout status deploy/postgres --timeout=180s

helm --kube-context "$CTX" install "$RELEASE" "$ROOT/infra/helm/radar" \
  -f "$ROOT/infra/helm/smoke/values-smoke.yaml" --set image.tag="$IMAGE_TAG" --wait --timeout 5m

DEPLOY=$(k get deploy -l "$LABEL" -o name)
SVC=$(k get svc -l "$LABEL" -o name)
k port-forward "$SVC" "$PORT:80" >/dev/null &
PF_PID=$!
for _ in $(seq 1 30); do
  body=$(curl -fsS "http://127.0.0.1:$PORT/actuator/health/readiness" 2>/dev/null) && break
  sleep 1
done
echo "readiness: ${body:-sem resposta}"
case "${body:-}" in *'"status":"UP"'*) ;; *) echo "readiness não respondeu UP" >&2; exit 1 ;; esac

# Graceful shutdown medido: o log do Pod tem de mostrar o desligamento gracioso e o Pod tem de
# sumir em menos de 25 s (o kubelet manda SIGKILL aos 30 s).
POD=$(k get pod -l "$LABEL" -o name)
k logs -f "$POD" >"$LOGS" 2>&1 &
LOGS_PID=$!
k scale "$DEPLOY" --replicas=0
SECONDS=0
k wait --for=delete "$POD" --timeout=40s
took=$SECONDS
wait "$LOGS_PID" 2>/dev/null || true
LOGS_PID=
echo "pod terminou em ${took}s"
[ "$took" -lt 25 ] || { echo "Pod levou ${took}s para sair (limite 25 s)" >&2; cat "$LOGS" >&2; exit 1; }
grep -q "Commencing graceful shutdown" "$LOGS" || { echo "log sem 'Commencing graceful shutdown'" >&2; exit 1; }
echo "smoke ok"

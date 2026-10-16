#!/usr/bin/env bash
# Terraform e tflint em containers com tag fixada. Nunca chama a AWS real: o plan usa LocalStack.
# Uso: scripts/terraform.sh fmt | validate | lint | plan
set -euo pipefail

TF_IMAGE=hashicorp/terraform:1.17.0
TFLINT_IMAGE=ghcr.io/terraform-linters/tflint:v0.65.0
LOCALSTACK_IMAGE=localstack/localstack:4.14

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TF_DIR="$ROOT/infra/terraform"
ENV_REL=envs/demo
# Arquivos criados pelo container ficam com o dono do host.
RUN_AS=(-u "$(id -u):$(id -g)" -e HOME=/tmp)

fmt() {
  docker run --rm "${RUN_AS[@]}" -v "$TF_DIR:/work" -w /work "$TF_IMAGE" fmt -check -recursive -no-color
}

validate() {
  docker run --rm "${RUN_AS[@]}" -v "$TF_DIR:/work" -w "/work/$ENV_REL" --entrypoint sh "$TF_IMAGE" \
    -c 'terraform init -backend=false -input=false -no-color && terraform validate -no-color'
}

lint() {
  docker run --rm "${RUN_AS[@]}" -e TFLINT_PLUGIN_DIR=/tmp/tflint.d -v "$TF_DIR:/data" -w /data \
    --entrypoint sh "$TFLINT_IMAGE" \
    -c 'tflint --config /data/.tflint.hcl --init && tflint --config /data/.tflint.hcl --recursive --format compact'
}

plan() {
  OUT="$ROOT/docs/infra/plan-$(date +%F).txt"
  NET="radar-tf-$$"
  LS="radar-localstack-$$"
  TMP=$(mktemp -d)
  trap "docker rm -f $LS >/dev/null 2>&1 || true; docker network rm $NET >/dev/null 2>&1 || true; rm -rf $TMP" EXIT

  # Copia o módulo sem .terraform e põe o override de LocalStack só na cópia temporária.
  mkdir "$TMP/terraform"
  (cd "$TF_DIR" && tar --exclude=.terraform -cf - .) | tar -C "$TMP/terraform" -xf -
  cp "$TF_DIR/localstack/localstack.tfoverride" "$TMP/terraform/$ENV_REL/localstack_override.tf"

  docker network create "$NET" >/dev/null
  docker run -d --name "$LS" --network "$NET" --network-alias localstack \
    -e SERVICES=ec2,iam,rds,s3,sts "$LOCALSTACK_IMAGE" >/dev/null

  ready=0
  for _ in $(seq 1 60); do
    health=$(docker run --rm --network "$NET" --entrypoint wget "$TF_IMAGE" -qO- http://localstack:4566/_localstack/health 2>/dev/null || true)
    case "$health" in
      *'"ec2": "available"'*) ready=1; break ;;
    esac
    sleep 3
  done
  if [ "$ready" -ne 1 ]; then
    echo "LocalStack não ficou com ec2 disponível em 180 s" >&2
    exit 1
  fi

  mkdir -p "$ROOT/docs/infra"
  # init vai para stderr; o plan legível vai para stdout (log do CI) e para um arquivo temporário.
  docker run --rm "${RUN_AS[@]}" --network "$NET" -v "$TMP/terraform:/work" -w "/work/$ENV_REL" --entrypoint sh "$TF_IMAGE" \
    -c 'terraform init -backend=false -input=false -no-color 1>&2 && terraform plan -input=false -no-color -var-file=terraform.tfvars.example' \
    | tee "$TMP/plan.txt"
  # Só substitui o plan versionado quando o plan terminou sem erro (set -o pipefail cobre o tee).
  mv "$TMP/plan.txt" "$OUT"
  echo "plan salvo em $OUT"
}

case "${1:-}" in
  fmt) fmt ;;
  validate) validate ;;
  lint) lint ;;
  plan) plan ;;
  *) echo "uso: $0 fmt|validate|lint|plan" >&2; exit 2 ;;
esac

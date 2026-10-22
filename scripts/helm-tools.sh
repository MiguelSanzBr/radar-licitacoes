#!/usr/bin/env bash
# Instala helm, kubeconform, kind e kubectl com versão fixada e checksum conferido.
# Reinstala em ${HELM_TOOLS_DIR:-$HOME/.local/bin} o que falta ali ou está em outra versão; quem chama
# põe esse diretório na frente do PATH. Só linux/amd64.
set -euo pipefail

HELM_VERSION=v4.3.1
HELM_SHA=0403a88ae20913d11b10a2467b50087721fea7581736da6b9d3992f5357fe54b
KUBECONFORM_VERSION=v0.8.0
KUBECONFORM_SHA=9bc2bffbf71f261128533edaf912153948b7ff238f9a531ae6d34466ec287883
KIND_VERSION=v0.33.0
KIND_SHA=aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d
KUBECTL_VERSION=v1.37.1
KUBECTL_SHA=65691ff77eb6fa44c908b77a1082c9f092c3b9733b5cefabec0d1104890e21a8

DEST=${HELM_TOOLS_DIR:-$HOME/.local/bin}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$DEST"

fetch() { # url sha out
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | sha256sum -c - >/dev/null || { echo "checksum diferente para $1" >&2; exit 1; }
}

has() { # nome, versão fixada: o binário de DEST responde com a versão esperada
  [ -x "$DEST/$1" ] && "$DEST/$1" "${@:3}" 2>/dev/null | grep -qF "$2"
}

has helm "$HELM_VERSION" version --short || {
  fetch "https://get.helm.sh/helm-$HELM_VERSION-linux-amd64.tar.gz" "$HELM_SHA" "$TMP/helm.tgz"
  tar -xzf "$TMP/helm.tgz" -C "$TMP" linux-amd64/helm && install -m 0755 "$TMP/linux-amd64/helm" "$DEST/helm"
}
has kubeconform "$KUBECONFORM_VERSION" -v || {
  fetch "https://github.com/yannh/kubeconform/releases/download/$KUBECONFORM_VERSION/kubeconform-linux-amd64.tar.gz" "$KUBECONFORM_SHA" "$TMP/kc.tgz"
  tar -xzf "$TMP/kc.tgz" -C "$TMP" kubeconform && install -m 0755 "$TMP/kubeconform" "$DEST/kubeconform"
}
has kind "$KIND_VERSION" version || {
  fetch "https://github.com/kubernetes-sigs/kind/releases/download/$KIND_VERSION/kind-linux-amd64" "$KIND_SHA" "$TMP/kind"
  install -m 0755 "$TMP/kind" "$DEST/kind"
}
has kubectl "$KUBECTL_VERSION" version --client || {
  fetch "https://dl.k8s.io/release/$KUBECTL_VERSION/bin/linux/amd64/kubectl" "$KUBECTL_SHA" "$TMP/kubectl"
  install -m 0755 "$TMP/kubectl" "$DEST/kubectl"
}

#!/usr/bin/env bats
# deploy.sh, rollback.sh e smoke.sh com docker e curl simulados no PATH: nada toca o daemon nem a rede.

setup() {
  ROOT="$BATS_TEST_DIRNAME/../.."
  BIN="$BATS_TEST_TMPDIR/bin"
  export STATE_DIR="$BATS_TEST_TMPDIR/state"
  export CALLS_LOG="$BATS_TEST_TMPDIR/calls.log"
  export RUNNING="$BATS_TEST_TMPDIR/running"
  export BAD_TAG=- MISSING_TAG=
  export PATH="$BIN:$PATH"
  mkdir -p "$BIN" "$STATE_DIR"
  : >"$CALLS_LOG"

  # docker simulado: registra cada chamada com a RADAR_TAG do ambiente; `compose up` marca a tag
  # que ficou no ar; a imagem MISSING_TAG nao existe localmente.
  cat >"$BIN/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $* RADAR_TAG=${RADAR_TAG:-}" >>"$CALLS_LOG"
case "$1" in
  image)
    ref="${*: -1}"
    [ "$ref" != "radar-licitacoes:${MISSING_TAG:-}" ] || { echo "No such image: $ref" >&2; exit 1; }
    echo "sha256:${ref##*:}" ;;
  compose)
    [ -z "${FAIL_COMPOSE_TAG:-}" ] || [ "${RADAR_TAG:-}" != "$FAIL_COMPOSE_TAG" ] || exit 1
    [ -z "${RADAR_TAG:-}" ] || echo "$RADAR_TAG" >"$RUNNING" ;;
esac
EOF

  # curl simulado: a leitura falha quando a tag no ar e BAD_TAG.
  cat >"$BIN/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl $*" >>"$CALLS_LOG"
[ "$(cat "$RUNNING" 2>/dev/null)" != "$BAD_TAG" ] || exit 22
EOF
  chmod +x "$BIN/docker" "$BIN/curl"
}

@test "deploy com sucesso grava .deployed e guarda o anterior" {
  printf 'tag=old\ndigest=sha256:old\n' >"$STATE_DIR/.deployed"

  run "$ROOT/scripts/deploy.sh" new

  [ "$status" -eq 0 ]
  grep -qx 'tag=new' "$STATE_DIR/.deployed"
  grep -qx 'digest=sha256:new' "$STATE_DIR/.deployed"
  grep -qx 'tag=old' "$STATE_DIR/.deployed.previous"
  grep -qx 'digest=sha256:old' "$STATE_DIR/.deployed.previous"
  grep -q 'actuator/info' "$CALLS_LOG"
  grep -q 'compose .*up .*RADAR_TAG=new' "$CALLS_LOG"
}

@test "smoke falho chama o rollback e sai com erro" {
  printf 'tag=old\ndigest=sha256:old\n' >"$STATE_DIR/.deployed"
  export BAD_TAG=new

  run "$ROOT/scripts/deploy.sh" new

  [ "$status" -ne 0 ]
  [[ "$output" == *"voltando ao deploy anterior"* ]]
  [[ "$output" == *"rollback: voltou para old"* ]]
  grep -qF 'docker tag sha256:old radar-licitacoes:rollback' "$CALLS_LOG"
  grep -q 'RADAR_TAG=rollback' "$CALLS_LOG"
  grep -qx 'tag=old' "$STATE_DIR/.deployed"
  [ ! -e "$STATE_DIR/.deployed.previous" ]
}

@test "rollback sem deploy anterior falha com mensagem" {
  run "$ROOT/scripts/rollback.sh"

  [ "$status" -ne 0 ]
  [[ "$output" == *"sem deploy anterior"* ]]
}

@test "rollback reimplanta o anterior pelo digest" {
  printf 'tag=old\ndigest=sha256:old\n' >"$STATE_DIR/.deployed.previous"
  printf 'tag=new\ndigest=sha256:new\n' >"$STATE_DIR/.deployed"

  run "$ROOT/scripts/rollback.sh"

  [ "$status" -eq 0 ]
  grep -qF 'docker tag sha256:old radar-licitacoes:rollback' "$CALLS_LOG"
  grep -q 'RADAR_TAG=rollback' "$CALLS_LOG"
  grep -qx 'tag=old' "$STATE_DIR/.deployed"
  grep -qx 'digest=sha256:old' "$STATE_DIR/.deployed"
  [ ! -e "$STATE_DIR/.deployed.previous" ]
}

@test "deploy sem a imagem local falha antes de tocar no compose" {
  export MISSING_TAG=new

  run "$ROOT/scripts/deploy.sh" new

  [ "$status" -ne 0 ]
  [[ "$output" == *"imagem radar-licitacoes:new ausente"* ]]
  run grep -q compose "$CALLS_LOG"
  [ "$status" -ne 0 ]
}

@test "deploy com RADAR_REGISTRY puxa a imagem e a grava com o nome local" {
  export RADAR_REGISTRY=ghcr.io/dono/radar-licitacoes

  run "$ROOT/scripts/deploy.sh" new

  [ "$status" -eq 0 ]
  grep -qF 'docker pull ghcr.io/dono/radar-licitacoes:new' "$CALLS_LOG"
  grep -qF 'docker tag ghcr.io/dono/radar-licitacoes:new radar-licitacoes:new' "$CALLS_LOG"
}

@test "redeploy da mesma imagem mantem o deploy anterior de verdade" {
  "$ROOT/scripts/deploy.sh" a
  "$ROOT/scripts/deploy.sh" b
  "$ROOT/scripts/deploy.sh" b

  grep -qx 'tag=b' "$STATE_DIR/.deployed"
  grep -qx 'tag=a' "$STATE_DIR/.deployed.previous"
}

@test "primeiro deploy que falha avisa que nao ha deploy anterior" {
  export FAIL_COMPOSE_TAG=new

  run "$ROOT/scripts/deploy.sh" new

  [ "$status" -ne 0 ]
  [[ "$output" == *"sem deploy anterior"* ]]
  [[ "$output" != *"voltando ao deploy anterior"* ]]
  [ ! -e "$STATE_DIR/.deployed" ]
}

@test "compose que falha no deploy volta ao anterior e mantem o estado" {
  printf 'tag=old\ndigest=sha256:old\n' >"$STATE_DIR/.deployed"
  export FAIL_COMPOSE_TAG=new

  run "$ROOT/scripts/deploy.sh" new

  [ "$status" -ne 0 ]
  [[ "$output" == *"voltando ao deploy anterior"* ]]
  grep -q 'RADAR_TAG=rollback' "$CALLS_LOG"
  grep -qx 'tag=old' "$STATE_DIR/.deployed"
}

@test "rollback com smoke falho sai com erro e mantem o estado" {
  printf 'tag=old\ndigest=sha256:old\n' >"$STATE_DIR/.deployed.previous"
  printf 'tag=new\ndigest=sha256:new\n' >"$STATE_DIR/.deployed"
  export BAD_TAG=rollback

  run "$ROOT/scripts/rollback.sh"

  [ "$status" -ne 0 ]
  [[ "$output" == *"nao subiu limpo"* ]]
  grep -qx 'tag=new' "$STATE_DIR/.deployed"
  grep -qx 'tag=old' "$STATE_DIR/.deployed.previous"
}

@test "smoke consulta o readiness e a leitura publica" {
  run "$ROOT/scripts/smoke.sh"

  [ "$status" -eq 0 ]
  grep -q 'actuator/health/readiness' "$CALLS_LOG"
  grep -q 'actuator/info' "$CALLS_LOG"
  grep -qF '/api/v1/procurements?limit=1' "$CALLS_LOG"
}

@test "smoke falho sai com erro e nomeia a rota" {
  printf 'new' >"$RUNNING"
  export BAD_TAG=new

  run "$ROOT/scripts/smoke.sh"

  [ "$status" -ne 0 ]
  [[ "$output" == *"smoke: /actuator/health/readiness falhou"* ]]
}

@test "smoke falha quando a leitura publica da API falha" {
  cat >"$BIN/curl" <<'EOF2'
#!/usr/bin/env bash
case "$*" in *api/v1/procurements*) exit 22 ;; esac
EOF2

  run "$ROOT/scripts/smoke.sh"

  [ "$status" -ne 0 ]
  [[ "$output" == *"smoke: /api/v1/procurements?limit=1 falhou"* ]]
}

@test "deploy nao deixa arquivo temporario do registro" {
  run "$ROOT/scripts/deploy.sh" new
  [ "$status" -eq 0 ]

  run find "$STATE_DIR" -name '.record.*'
  [ -z "$output" ]
}

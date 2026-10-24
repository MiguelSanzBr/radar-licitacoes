#!/usr/bin/env bats
# scripts/probe-guard.sh: a sonda so roda com PROBE_CONFIRM=1.

setup() {
  GUARD="$BATS_TEST_DIRNAME/../probe-guard.sh"
}

@test "sem PROBE_CONFIRM recusa e explica o motivo" {
  run env -u PROBE_CONFIRM "$GUARD"
  [ "$status" -eq 1 ]
  [[ "$output" == *"429"* ]]
  [[ "$output" == *"0,5 req/s"* ]]
  [[ "$output" == *"ADR-0005"* ]]
  [[ "$output" == *"ADR-0017"* ]]
  [[ "$output" == *"PROBE_CONFIRM=1"* ]]
}

@test "com PROBE_CONFIRM diferente de 1 tambem recusa" {
  run env PROBE_CONFIRM=yes "$GUARD"
  [ "$status" -eq 1 ]
}

@test "com PROBE_CONFIRM=1 libera" {
  run env PROBE_CONFIRM=1 "$GUARD"
  [ "$status" -eq 0 ]
  [ -z "$output" ]
}

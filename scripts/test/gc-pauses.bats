#!/usr/bin/env bats
# scripts/gc-pauses.sh sobre trechos de log do Serial, do G1 e do ZGC.

setup() {
  GC="$BATS_TEST_DIRNAME/../gc-pauses.sh"
  FIX="$BATS_TEST_DIRNAME/fixtures/gc"
}

@test "Serial: conta as paradas Young e Full" {
  run "$GC" "$FIX/serial.log"
  [ "$status" -eq 0 ]
  [ "$output" = '{"count":3,"p50Ms":20.000,"p99Ms":100.000,"maxMs":100.000,"totalMs":130.000}' ]
}

@test "G1: Remark conta e fase concorrente nao" {
  run "$GC" "$FIX/g1.log"
  [ "$output" = '{"count":2,"p50Ms":1.500,"p99Ms":3.000,"maxMs":3.000,"totalMs":4.500}' ]
}

@test "ZGC: so as fases Pause de gc,phases" {
  run "$GC" "$FIX/zgc.log"
  [ "$output" = '{"count":3,"p50Ms":0.020,"p99Ms":0.030,"maxMs":0.030,"totalMs":0.060}' ]
}

@test "a janela de tempo recorta as paradas" {
  run "$GC" "$FIX/serial.log" 2026-10-23T12:00:01.500 2026-10-23T12:00:03.000
  [ "$output" = '{"count":2,"p50Ms":20.000,"p99Ms":100.000,"maxMs":100.000,"totalMs":120.000}' ]
}

@test "log sem pausas devolve zeros" {
  run "$GC" "$FIX/serial.log" 2030-01-01T00:00:00.000 2030-01-02T00:00:00.000
  [ "$output" = '{"count":0,"p50Ms":0,"p99Ms":0,"maxMs":0,"totalMs":0}' ]
}

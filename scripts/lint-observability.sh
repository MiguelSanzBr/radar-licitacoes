#!/usr/bin/env bash
# Valida dashboards Grafana (JSON, variavel de datasource, sem uid fixo), config e regras do Prometheus
# e os testes unitarios das regras. promtool roda na imagem com tag fixada.
# Uso: scripts/lint-observability.sh
set -euo pipefail

PROM_IMAGE=prom/prometheus:v3.13.4

ROOT=$(cd "$(dirname "$0")/.." && pwd)
PROM_DIR="$ROOT/infra/prometheus"
DASH_DIR="$ROOT/infra/grafana/dashboards"

DASHBOARD_POLICY=$(cat <<'PY'
import json, pathlib, sys

root = pathlib.Path(sys.argv[1])
files = sorted(root.glob("*.json"))
errors = []
uids = set()

if not files:
    errors.append(f"nenhum dashboard em {root}")

def datasource_refs(node, path):
    """Devolve (caminho, valor) de cada chave datasource dentro de panels, em qualquer profundidade."""
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "datasource":
                yield path, value
            yield from datasource_refs(value, f"{path}.{key}")
    elif isinstance(node, list):
        for i, value in enumerate(node):
            yield from datasource_refs(value, f"{path}[{i}]")

def all_panels(panels):
    """Devolve cada panel, inclusive os aninhados em rows."""
    for panel in panels:
        yield panel
        yield from all_panels(panel.get("panels", []))

for f in files:
    try:
        dash = json.loads(f.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        errors.append(f"{f.name}: JSON invalido: {exc}")
        continue
    if not isinstance(dash, dict) or not dash.get("title") or not dash.get("uid"):
        errors.append(f"{f.name}: falta title ou uid")
        continue
    if dash["uid"] in uids:
        errors.append(f"{f.name}: uid duplicado {dash['uid']}")
    uids.add(dash["uid"])
    if not isinstance(dash.get("schemaVersion"), int):
        errors.append(f"{f.name}: schemaVersion ausente")

    variables = [v for v in dash.get("templating", {}).get("list", [])
                 if v.get("type") == "datasource" and v.get("query") == "prometheus"]
    if not variables:
        errors.append(f"{f.name}: sem variavel de datasource do tipo prometheus")
        continue
    allowed = {"${" + v["name"] + "}" for v in variables}

    for panel in all_panels(dash.get("panels", [])):
        if panel.get("targets") and "datasource" not in panel:
            errors.append(f"{f.name}: panel {panel.get('title')!r} tem targets sem datasource")

    refs = list(datasource_refs(dash.get("panels", []), "panels"))
    if not refs:
        errors.append(f"{f.name}: nenhuma referencia de datasource nos panels")
    for path, ds in refs:
        uid = ds.get("uid") if isinstance(ds, dict) else None
        if uid not in allowed:
            errors.append(f"{f.name}: {path} usa datasource fixo ou fora da variavel: {ds!r}")

if errors:
    print("\n".join(errors), file=sys.stderr)
    sys.exit(1)
print(f"dashboards: ok ({len(files)} arquivos)")
PY
)

run_promtool() {
  docker run --rm -v "$PROM_DIR:/etc/prometheus:ro" --entrypoint promtool "$PROM_IMAGE" "$@"
}

python3 -I -c "$DASHBOARD_POLICY" "$DASH_DIR"
run_promtool check config /etc/prometheus/prometheus.yml
run_promtool check rules /etc/prometheus/rules/slo.yml
run_promtool test rules /etc/prometheus/rules/slo_test.yml
echo "lint-observability: ok"

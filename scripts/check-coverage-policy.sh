#!/usr/bin/env bash
# Falha (exit 1) se algum pom.xml versionado tiver exclusao de cobertura ou de testes
# (JaCoCo ou PIT) que nao esteja listada em docs/coverage-exclusions.md.
# Uso: check-coverage-policy.sh [raiz]   |   check-coverage-policy.sh --self-test
set -euo pipefail

check() {
  local root=$1
  git -C "$root" ls-files -z '*pom.xml' | python3 -c "$POLICY" "$root"
}

POLICY=$(cat <<'PY'
import pathlib, re, sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
ns = re.compile(r"^\{.*\}")
local = lambda el: ns.sub("", el.tag)
plugins = {"jacoco-maven-plugin", "pitest-maven"}
# Containers cujas folhas (texto de <exclude>, <param>, <excludedClass>...) sao exclusoes.
excluding = {"exclude", "excludes", "excludedClasses", "excludedMethods", "excludedTestClasses"}
# Containers onde so as entradas negadas com "!" sao exclusoes.
negating = {"targetClasses", "targetTests"}

def leaves(el):
    if len(el) == 0:
        text = (el.text or "").strip()
        if text:
            yield text
    for child in el:
        yield from leaves(child)

doc = root / "docs" / "coverage-exclusions.md"
allowed = set()
if doc.is_file():
    for line in doc.read_text(encoding="utf-8").splitlines():
        if not line.lstrip().startswith("|"):
            continue
        cell = line.strip().strip("|").split("|")[0].strip().strip("`")
        if cell and cell != "padrão" and not set(cell) <= set("-: "):
            allowed.add(cell)

bad = set()
for rel in filter(None, sys.stdin.buffer.read().decode().split("\0")):
    for plugin in ET.parse(root / rel).getroot().iter():
        if local(plugin) != "plugin":
            continue
        artifact = next((c.text.strip() for c in plugin if local(c) == "artifactId" and c.text), "")
        if artifact not in plugins:
            continue
        for el in plugin.iter():
            name = local(el)
            if name in excluding:
                values = leaves(el)
            elif name in negating:
                values = (v for v in leaves(el) if v.startswith("!"))
            else:
                continue
            bad.update(f"{rel}: {artifact} exclui '{v}'" for v in values if v not in allowed)

if bad:
    print("Exclusoes fora de docs/coverage-exclusions.md:", file=sys.stderr)
    print("\n".join(sorted(bad)), file=sys.stderr)
    sys.exit(1)
PY
)

# self_case <esperado: pass|fail> <descricao> <trecho de configuracao do plugin> [padrao listado]
self_case() {
  local expected=$1 label=$2 plugin=$3 listed=${4:-} dir rc=0
  dir=$(mktemp -d)
  mkdir -p "$dir/docs"
  printf '| padrão | motivo |\n|---|---|\n' > "$dir/docs/coverage-exclusions.md"
  [[ -n "$listed" ]] && printf '| `%s` | gerado |\n' "$listed" >> "$dir/docs/coverage-exclusions.md"
  printf '<project><build><plugins>%s</plugins></build></project>\n' "$plugin" > "$dir/pom.xml"
  git -C "$dir" init -q && git -C "$dir" add -A
  check "$dir" 2>/dev/null || rc=$?
  rm -rf "$dir"
  if [[ $expected == pass && $rc -ne 0 ]] || [[ $expected == fail && $rc -eq 0 ]]; then
    echo "self-test: caso '$label' esperava $expected" >&2
    exit 1
  fi
}

self_test() {
  local jac='<plugin><artifactId>jacoco-maven-plugin</artifactId><configuration><excludes><exclude>**/Foo.class</exclude></excludes></configuration></plugin>'
  local pit='<plugin><artifactId>pitest-maven</artifactId><configuration><excludedClasses><param>x.Foo</param></excludedClasses></configuration></plugin>'
  local tst='<plugin><artifactId>pitest-maven</artifactId><configuration><excludedTestClasses><param>x.FooTest</param></excludedTestClasses></configuration></plugin>'
  local neg='<plugin><artifactId>pitest-maven</artifactId><configuration><targetClasses><param>x.*</param><param>!x.Foo</param></targetClasses></configuration></plugin>'
  local pos='<plugin><artifactId>pitest-maven</artifactId><configuration><targetClasses><param>x.*</param></targetClasses></configuration></plugin>'
  self_case fail 'jacoco exclude nao listado' "$jac"
  self_case pass 'jacoco exclude listado' "$jac" '**/Foo.class'
  self_case fail 'pit param nao listado' "$pit"
  self_case pass 'pit param listado' "$pit" 'x.Foo'
  self_case fail 'pit excludedTestClasses nao listado' "$tst"
  self_case pass 'pit excludedTestClasses listado' "$tst" 'x.FooTest'
  self_case fail 'pit targetClasses negado nao listado' "$neg"
  self_case pass 'pit targetClasses negado listado' "$neg" '!x.Foo'
  self_case pass 'pit targetClasses positivo' "$pos"
  echo "self-test ok"
}

if [[ "${1:-}" == "--self-test" ]]; then
  self_test
else
  check "${1:-$(cd "$(dirname "$0")/.." && pwd)}"
fi

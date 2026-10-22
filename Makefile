.PHONY: verify mutation check-coverage-policy lint-openapi audit-repo tf-fmt tf-validate tf-lint tf-plan up down replay bench slo synth probe deploy rollback profile image image-size image-scan image-test lint-observability probe-report backfill helm-lint helm-smoke sbom sbom-scan secrets-scan

verify:
	./mvnw verify

TRIVY_IMAGE := aquasec/trivy:0.76.0@sha256:2416d1f74045b57274df1a340eee6b510cacd73c66a6ccb8da092b117eb7d222

image:
	docker build -t radar-licitacoes:local .

image-size:
	scripts/image-size.sh

# Teste de container contra radar-licitacoes:local (rode `make image` antes).
image-test:
	./mvnw -Pimage -pl it-image verify

# Falha em CRITICAL ou HIGH com correcao disponivel, na imagem final e na imagem de build (JDK
# fixado no Dockerfile). O tar evita montar o socket do Docker. O Trivy nao confere o JDK dentro
# do runtime jlink (ver ADR-0022): a mitigacao e acompanhar os avisos do Temurin e subir o digest.
JDK_BUILD_IMAGE = $(shell sed -n 's/^FROM \(eclipse-temurin[^ ]*\) AS build$$/\1/p' Dockerfile)

TRIVY = docker run --rm -v "$$HOME/.cache/trivy":/root/.cache/trivy $(TRIVY_IMAGE)
TRIVY_FLAGS = --severity CRITICAL,HIGH --ignore-unfixed --exit-code 1 --skip-version-check

image-scan:
	@tar="$$(mktemp -u).tar"; trap 'rm -f "$$tar"' EXIT; \
	docker save -o "$$tar" radar-licitacoes:local; \
	docker run --rm -v "$$tar":/image.tar:ro -v "$$HOME/.cache/trivy":/root/.cache/trivy $(TRIVY_IMAGE) \
	  image --input /image.tar $(TRIVY_FLAGS) && \
	$(TRIVY) image $(JDK_BUILD_IMAGE) $(TRIVY_FLAGS)

# Varreduras de cadeia de suprimento e segredos (docs/security/scans.md). Imagens fixadas por digest.
GRYPE_IMAGE := anchore/grype:v0.121.0@sha256:621d56d1d68106b787022c81546f7e0a36202a25d1239f0c20195fab8c16f7ea
GITLEAKS_IMAGE := zricethezav/gitleaks:v8.30.1@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f
SBOM_FILE := target/classes/META-INF/sbom/application.cdx.json
# Worktrees apontam o .git para o repositorio principal: esse diretorio entra no mesmo caminho.
GIT_COMMON_DIR = $(abspath $(shell git rev-parse --git-common-dir))

sbom:
	./mvnw -B -q -DskipTests package

# Grype sobre o SBOM agregado (CycloneDX no package); falha em CRITICAL. Ignores justificados em .grype.yaml.
sbom-scan: sbom
	mkdir -p "$$HOME/.cache/grype"
	docker run --rm -u "$$(id -u):$$(id -g)" -e HOME=/tmp -e GRYPE_DB_CACHE_DIR=/cache -e TMPDIR=/cache \
	  -v "$$HOME/.cache/grype:/cache" -v "$(CURDIR):$(CURDIR)" -w "$(CURDIR)" \
	  $(GRYPE_IMAGE) sbom:$(SBOM_FILE) -c .grype.yaml --fail-on critical

# Gitleaks no historico completo e na arvore de trabalho; regras e allowlist em .gitleaks.toml.
secrets-scan:
	docker run --rm -u "$$(id -u):$$(id -g)" -e HOME=/tmp \
	  -v "$(CURDIR):$(CURDIR)" -v "$(GIT_COMMON_DIR):$(GIT_COMMON_DIR)" -w "$(CURDIR)" \
	  $(GITLEAKS_IMAGE) git --config .gitleaks.toml --log-opts=--all --redact --exit-code 1 --log-level warn --verbose "$(CURDIR)"
	docker run --rm -u "$$(id -u):$$(id -g)" -e HOME=/tmp \
	  -v "$(CURDIR):$(CURDIR)" -w "$(CURDIR)" \
	  $(GITLEAKS_IMAGE) dir --config .gitleaks.toml --redact --exit-code 1 --log-level warn --verbose "$(CURDIR)"

mutation:
	./mvnw -Pmutation verify

check-coverage-policy:
	scripts/check-coverage-policy.sh --self-test
	scripts/check-coverage-policy.sh

lint-observability:
	scripts/lint-observability.sh

lint-openapi:
	npx --yes @stoplight/spectral-cli@6 lint contracts/src/main/resources/openapi/radar-api.v1.yaml --fail-severity=warn

# Os termos proibidos ficam num arquivo local, nao versionado, no diretorio de hooks do git.
audit-repo:
	@terms="$$(git rev-parse --git-common-dir)/hooks/terms.txt"; \
	if [ ! -f "$$terms" ]; then echo "audit-repo: $$terms ausente, nada a verificar" >&2; exit 0; fi; \
	patterns=$$(mktemp); trap 'rm -f "$$patterns"' EXIT; \
	grep -v '^[[:space:]]*$$' "$$terms" > "$$patterns"; \
	if git ls-files | grep -iEf "$$patterns"; then echo "audit-repo: caminho versionado com termo proibido" >&2; exit 1; fi; \
	if git ls-files -z | xargs -0 grep -IilEf "$$patterns"; then echo "audit-repo: conteudo versionado com termo proibido" >&2; exit 1; fi; \
	echo "audit-repo: ok"

# helm lint + kubeconform -strict sobre o chart; instala as ferramentas fixadas se faltarem.
helm-lint:
	scripts/helm-lint.sh

# Sobe um cluster kind, instala o chart e espera o readiness (apaga o cluster ao sair).
helm-smoke:
	scripts/helm-smoke.sh

tf-fmt:
	scripts/terraform.sh fmt

tf-validate:
	scripts/terraform.sh validate

tf-lint:
	scripts/terraform.sh lint

# Plan contra LocalStack; nunca aplica nem usa credencial real.
tf-plan:
	scripts/terraform.sh plan

# Sonda do PNCP: `make probe` roda em segundo plano (PROBE_DURATION, PROBE_OUT sobrescrevem os padroes).
# Precisa de JDK 25 no PATH ou em JAVA_HOME.
PROBE_DURATION ?= PT24H
PROBE_OUT ?= tools/pncp-probe/out
PROBE_JAR := tools/pncp-probe/target/pncp-probe.jar
PROBE_JAVA := $(if $(JAVA_HOME),$(JAVA_HOME)/bin/java,java)

probe:
	@if [ -f $(PROBE_OUT)/probe.pid ] && kill -0 $$(cat $(PROBE_OUT)/probe.pid) 2>/dev/null; then \
		echo "probe: ja em execucao (PID $$(cat $(PROBE_OUT)/probe.pid))" >&2; exit 1; fi
	./mvnw -q -pl tools/pncp-probe -am package -DskipTests
	mkdir -p $(PROBE_OUT)
	nohup $(PROBE_JAVA) -jar $(PROBE_JAR) run --duration=$(PROBE_DURATION) --out=$(PROBE_OUT) >> $(PROBE_OUT)/probe.log 2>&1 & echo $$! > $(PROBE_OUT)/probe.pid
	@echo "probe: PID $$(cat $(PROBE_OUT)/probe.pid), log em $(PROBE_OUT)/probe.log"

probe-report:
	$(PROBE_JAVA) -jar $(PROBE_JAR) report --in=$(PROBE_OUT) --to=docs/benchmarks/probe/$$(date +%F).md

up down replay bench slo synth deploy rollback profile:
	@echo "$@: not implemented yet" >&2
	@exit 1

# Uso: make backfill FROM=2026-10-01 TO=2026-10-31 [MODALITY=6]; force=true ignora a janela noturna.
backfill:
	curl -fsS -X POST "http://localhost:8080/ops/backfill?from=$(FROM)&to=$(TO)&modality=$${MODALITY:-6}&force=true"

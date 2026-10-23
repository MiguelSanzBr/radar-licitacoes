.PHONY: verify chaos mutation check-coverage-policy lint-openapi audit-repo tf-fmt tf-validate tf-lint tf-plan up down logs seed e2e replay bench slo synth probe deploy rollback test-scripts profile image image-size image-scan image-test lint-observability probe-report backfill helm-lint helm-smoke sbom sbom-scan secrets-scan

verify:
	./mvnw verify

# Prova de caos (ADR-0017): 10 mil eventos com Toxiproxy; Postgres e Kafka proprios, nunca os compartilhados.
# Rode sozinho (uns 4 GB livres) e leia as perdas e duplicatas impressas no fim (ambas devem ser 0).
chaos:
	./mvnw -Pchaos verify -pl core -am -Dit.test=ChaosIT

TRIVY_IMAGE := aquasec/trivy:0.76.0@sha256:2416d1f74045b57274df1a340eee6b510cacd73c66a6ccb8da092b117eb7d222

# O codegen do jOOQ precisa de Docker (Testcontainers) e roda no host: o build da imagem nao o tem.
image:
	./mvnw -q compile -pl core -am -DskipTests
	docker build -t radar-licitacoes:$(RADAR_TAG) .

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

# Massa sintetica rotulada (tools/synth, ADR-0005): `make synth SYNTH_COUNT=600000 SYNTH_SEED=42` carrega o read
# model do Postgres do compose (senha do arquivo de segredo ou de RADAR_DB_PASSWORD, nunca no argv);
# `make synth SYNTH_MODE=events` grava os envelopes em SYNTH_OUT para o replay. Corpus: infra/compose/seed.
SYNTH_COUNT ?= 600000
SYNTH_SEED ?= 42
SYNTH_MODE ?= copy
SYNTH_OUT ?= data/synth-events.jsonl.gz
SYNTH_JDBC_URL ?= jdbc:postgresql://localhost:5432/radar
SYNTH_USER ?= radar
SYNTH_JAR := tools/synth/target/synth.jar

synth:
	./mvnw -q -pl tools/synth -am package -DskipTests
	@pw="$${RADAR_DB_PASSWORD:-$$(cat infra/compose/secrets/db_password 2>/dev/null)}"; \
	[ "$(SYNTH_MODE)" != copy ] || [ -n "$$pw" ] || { echo "synth: defina RADAR_DB_PASSWORD ou rode make up (infra/compose/secrets/db_password)" >&2; exit 1; }; \
	RADAR_DB_PASSWORD="$$pw" $(PROBE_JAVA) -jar $(SYNTH_JAR) --mode=$(SYNTH_MODE) --count=$(SYNTH_COUNT) --seed=$(SYNTH_SEED) \
	  --out=$(SYNTH_OUT) --jdbc-url=$(SYNTH_JDBC_URL) --user=$(SYNTH_USER)

replay bench slo profile:
	@echo "$@: not implemented yet" >&2
	@exit 1

# Ambiente local completo (ADR-0022): cria .env e segredos se faltarem, constroi a imagem se nao
# existir e sobe tudo esperando os healthchecks. Camada 3: PROFILE=layer3 make up.
# RADAR_TAG e a unica fonte da tag: o make exporta e o compose e o e2e leem do ambiente.
export RADAR_TAG ?= local
COMPOSE = docker compose -f infra/compose/docker-compose.yml

up:
	scripts/compose-init.sh
	docker image inspect radar-licitacoes:$(RADAR_TAG) >/dev/null 2>&1 || $(MAKE) image
	$(COMPOSE) $(if $(PROFILE),--profile $(PROFILE)) up -d --wait

# Para tudo, inclusive os servicos de perfil (apicurio); apaga os volumes somente com CLEAN=1.
down:
	$(COMPOSE) --profile '*' down $(if $(filter 1,$(CLEAN)),-v)

logs:
	$(COMPOSE) --profile '*' logs -f --tail=100 $(SERVICE)

# Carrega o dia gravado em infra/compose/seed pelo backfill do operador (client credentials do realm).
SEED_DAY ?= 2026-10-21
seed:
	scripts/seed.sh $(SEED_DAY)

# Sobe o compose e roda o fluxo ponta a ponta; precisa da imagem (make image) e das portas livres.
e2e:
	./mvnw -q -Pe2e verify -pl e2e -am

# Deploy por tag e volta ao anterior (ADR-0022, secao Deploy e rollback). O CI roda os mesmos comandos:
# RADAR_REGISTRY (ghcr.io/<dono>/radar-licitacoes) faz o pull; sem ele, usa a imagem local radar-licitacoes:TAG.
deploy:
	scripts/deploy.sh "$(TAG)"

rollback:
	scripts/rollback.sh

# Shellcheck e bats pelas imagens fixadas (nada instalado no host). Os testes simulam docker e curl.
SHELLCHECK_IMAGE := koalaman/shellcheck:v0.10.0@sha256:2097951f02e735b613f4a34de20c40f937a6c8f18ecb170612c88c34517221fb
BATS_IMAGE := bats/bats:1.12.0@sha256:0257e4f5326dd37046e94d4b8ce07c293447be80a9db6d6dfff1d50d07617e2e

test-scripts:
	docker run --rm -v "$(CURDIR):$(CURDIR)" -w "$(CURDIR)" $(SHELLCHECK_IMAGE) scripts/deploy-lib.sh scripts/deploy.sh scripts/rollback.sh scripts/smoke.sh
	docker run --rm -v "$(CURDIR):$(CURDIR)" -w "$(CURDIR)" $(BATS_IMAGE) scripts/test

# Uso: make backfill FROM=2026-10-01 TO=2026-10-31 [MODALITY=6]; force=true ignora a janela noturna.
backfill:
	curl -fsS -X POST -H "Authorization: Bearer $$(scripts/ops-token.sh)" "http://localhost:8080/ops/backfill?from=$(FROM)&to=$(TO)&modality=$${MODALITY:-6}&force=true"

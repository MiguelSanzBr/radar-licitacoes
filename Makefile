.PHONY: verify mutation check-coverage-policy lint-openapi audit-repo tf-fmt tf-validate tf-lint tf-plan up down backfill replay bench slo synth probe deploy rollback profile image image-size image-scan image-test

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

mutation:
	./mvnw -Pmutation verify

check-coverage-policy:
	scripts/check-coverage-policy.sh --self-test
	scripts/check-coverage-policy.sh

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

tf-fmt:
	scripts/terraform.sh fmt

tf-validate:
	scripts/terraform.sh validate

tf-lint:
	scripts/terraform.sh lint

# Plan contra LocalStack; nunca aplica nem usa credencial real.
tf-plan:
	scripts/terraform.sh plan

up down backfill replay bench slo synth probe deploy rollback profile:
	@echo "$@: not implemented yet" >&2
	@exit 1

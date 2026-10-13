.PHONY: verify mutation check-coverage-policy lint-openapi audit-repo up down backfill replay bench slo synth probe deploy rollback profile

verify:
	./mvnw verify

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

up down backfill replay bench slo synth probe deploy rollback profile:
	@echo "$@: not implemented yet" >&2
	@exit 1

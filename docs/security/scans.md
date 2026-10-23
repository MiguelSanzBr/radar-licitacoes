# Varreduras de cadeia de suprimento e segredos

Este documento descreve as varreduras de SBOM, dependências, imagem e segredos do projeto. A decisão de identidade e acesso está no ADR 0019 (`docs/adr/0019-seguranca.md`); a checklist OWASP Top 10, com o teste ou a mitigação de cada item, está em `docs/security/owasp.md`.

## O que cada varredura cobre

| Varredura | Ferramenta (imagem fixada) | Alvo | Falha quando | Como rodar |
|-----------|----------------------------|------|--------------|------------|
| SBOM | CycloneDX (plugin no `package`) | dependências Maven agregadas, em `target/classes/META-INF/sbom/application.cdx.json` | não gera o insumo | `make sbom` |
| Dependências | Grype `v0.121.0` | SBOM acima | vulnerabilidade CRITICAL (`--fail-on critical`) | `make sbom-scan` |
| Segredos | Gitleaks `v8.30.1` | histórico git completo (`git --log-opts=--all`) e árvore de trabalho (`dir`) | qualquer segredo detectado | `make secrets-scan` |
| Imagem | Trivy `0.76.0` | imagem final e imagem de build | CRITICAL ou HIGH com correção disponível | `make image-scan` |

Os digests estão fixados no `Makefile` (`GRYPE_IMAGE`, `GITLEAKS_IMAGE`, `TRIVY_IMAGE`). Os dois jobs correspondentes estão no `ci.yml`: `supply-chain` roda `make sbom-scan` e `make secrets-scan`, e `image` roda `make image-scan`.

## Como rodar localmente

Pré-requisitos: Docker e JDK 25 (`JAVA_HOME` apontando para ele, já que `make sbom-scan` chama `./mvnw`).

```
make sbom-scan
make secrets-scan
```

`make sbom-scan` gera o SBOM antes da varredura (`package` com `-DskipTests`). O banco de vulnerabilidades do Grype fica em `~/.cache/grype`.

## Como adicionar um ignore justificado

Um ignore deve ser exceção rara. Ele precisa de comentário com motivo, dono e data de expiração, no formato `YYYY-MM-DD`:

- Grype: entrada em `.grype.yaml`, com o comentário logo acima da entrada. Hoje a lista está vazia (`ignore: []`); ao adicionar a primeira, troque `[]` pela lista.

  ```yaml
  ignore:
    # Motivo: ... Dono: ... Expira em 2027-01-31: reavaliar na subida da versão X.
    - vulnerability: GHSA-xxxx-xxxx-xxxx
  ```

- Gitleaks: regra exata em `.gitleaks.toml`, só para defaults de desenvolvimento (por exemplo, a senha do Postgres local do `docker compose`). O Gitleaks não tem expiração nativa, então o comentário traz o motivo e o dono, e a lista é revisada a cada release.

  ```toml
  [[allowlists]]
  description = "Motivo e dono da exceção"
  regexes = ['''valor-exato-de-dev''']
  ```

Nunca use allowlist para segredo real. Um valor real que vazou deve ser revogado e removido do histórico, não só ignorado.

## Resultado atual (2026-10-22)

- SBOM: 166 componentes, CycloneDX 1.6.
- Grype `v0.121.0`: nenhum aviso. `make sbom-scan` passa sem ignore. O `lz4-java` estava em 1.11.2 (HIGH `GHSA-mcr4-qmvw-px4g`, MEDIUM `GHSA-gm45-99xc-r7wv`, LOW `GHSA-343h-94h5-c4wr`); o `pom.xml` agora fixa `at.yawk.lz4:lz4-java` em 1.11.4 no `dependencyManagement`, com comentário para remover quando o Kafka gerenciado trouxer 1.11.4 ou mais.
- Gitleaks `v8.30.1` com as regras padrão: nenhum vazamento no histórico nem na árvore de trabalho. Nenhuma allowlist foi necessária.
- Verificação negativa: uma chave AWS falsa colocada na árvore de trabalho faz `make secrets-scan` falhar. A mesma varredura do histórico detecta um segredo que foi removido num commit posterior.
- Trivy: sem mudança neste PR. `--exit-code 1`, `--severity CRITICAL,HIGH` e `--ignore-unfixed` já estão no `Makefile`. Em 2026-10-22, `make image` e `make image-scan` passaram localmente (exit 0), com `lz4-java-1.11.4.jar` sem vulnerabilidades.

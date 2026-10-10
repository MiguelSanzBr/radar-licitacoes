# Exclusões de cobertura

Regra: somente código gerado anotado com `@Generated` pode ser excluído do gate de cobertura (JaCoCo e PIT). Nenhuma exclusão manual de pacote ou classe.

Toda `<exclude>` em `pom.xml` precisa constar nesta tabela, com o motivo. O script `scripts/check-coverage-policy.sh` falha o build se houver exclusão fora da lista.

| padrão | motivo |
|---|---|

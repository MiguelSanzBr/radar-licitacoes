# ADR-0009: Java moderno no domínio, sem Lombok

- Status: aceita
- Data: 2026-10-11

## Contexto

O domínio guarda as regras que mais importam e que mais precisam de teste: formato do número de controle do PNCP, valores em reais, siglas de UF, eventos. Hierarquias clássicas (classes abstratas, getters, `equals` e `hashCode` escritos à mão, `instanceof` seguido de cast) escondem a regra no meio de código repetitivo e deixam o compilador de fora da verificação. O Java 25 já traz o que é preciso para modelar dados e variantes de forma explícita.

## Decisão

- Objetos de valor e eventos são `record`. A validação fica no construtor compacto e o objeto nasce válido ou não nasce (`ProcurementId`, `Money`, `IbgeCode`, `Email`, `EventEnvelope`).
- Famílias fechadas de variantes (estados da licitação, eventos de cada contexto) usam `sealed interface` com `record` como casos. `switch` com padrões sobre um tipo selado dispensa `default`, então um caso novo quebra a compilação onde não for tratado.
- `DomainEvent` do kernel é uma interface comum, não selada: um tipo selado só pode permitir subtipos de outro pacote dentro de um módulo nomeado, e o projeto roda no módulo sem nome. Cada contexto declara a própria família selada que implementa `DomainEvent`.
- Enums para conjuntos fixos (`Uf`) e `switch` com padrões no lugar de cadeias de `instanceof`.
- Nenhum Lombok. O compilador gera o que um `record` já oferece, e o restante se escreve à mão.
- Relógio e aleatoriedade entram por `java.time.InstantSource` e `java.util.random.RandomGenerator`, injetados. A biblioteca padrão já é a porta, sem interface própria.

## Alternativas rejeitadas (e por quê)

- Lombok (`@Value`, `@Builder`): é um processador de anotações que altera a árvore sintática do compilador, atrasa a adoção de cada versão nova do Java e esconde o código real de ferramentas de análise e de cobertura. O que ele entrega, `record` já entrega sem plugin.
- Classes imutáveis escritas à mão: mais linhas, mais chance de `equals` e `hashCode` divergirem do estado e mais código sem valor para cobrir e mutar.
- Hierarquia aberta de eventos com `instanceof` e cast: um subtipo novo passa sem aviso por todo `if` existente. Com tipo selado, o compilador aponta cada lugar a revisar.
- Porta `Clock` própria: duplica `InstantSource` sem acrescentar nada, e os testes já controlam o tempo com `InstantSource.fixed`.

## Consequências

- Menos código no domínio e regras de formato concentradas em um único construtor por tipo.
- Teste de propriedade (jqwik) cobre as invariantes dos objetos de valor, e o ganho aparece como mutação alta com cobertura de 100%.
- Quem usa ferramenta de mapeamento (JSON, JPA, jOOQ) faz isso nos adapters, nunca anotando o domínio.
- Custo aceito: `record` não tem herança de estado nem construtor privado de fábrica com validação diferente por caminho. Quando uma regra exigir isso, a regra vira um tipo próprio.

## Prova

- Testes em `core/src/test/java/br/dev/miguelsanzbr/radar/shared/`, incluindo as propriedades de `MoneyPropertiesTest`, `UfPropertiesTest`, `EmailPropertiesTest` e `UuidV7PropertiesTest`.
- Relatório de mutação do módulo: `./mvnw -Pmutation verify -pl core`.

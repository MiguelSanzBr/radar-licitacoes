# ADR-0001: Registro de decisões e o que exige ADR

- Status: aceita
- Data: 2026-10-11

## Contexto

O projeto é desenvolvido por uma pessoa só. Sem registro, o motivo de cada escolha se perde, e a revisão futura depende de lembrança. Também é preciso um critério claro para saber quando uma escolha merece registro e quando é detalhe de implementação.

## Decisão

- Cada decisão vai para `docs/adr/NNNN-titulo.md`, a partir de `docs/adr/template.md`. Os números são sequenciais e não são reaproveitados.
- Exige ADR toda escolha entre alternativas reais com custo: tecnologia, padrão de arquitetura, política de dados, formato de evento, limite numérico ou regra de processo.
- Uma capacidade só é considerada pronta com código, teste ou benchmark e ADR. Sem as três coisas, ela continua em andamento.
- Status possíveis:
  - `proposta`: em discussão, ainda não vale para o código.
  - `aceita`: vale para o código.
  - `substituída por ADR-NNNN`: a decisão foi trocada. A ADR antiga não é reescrita para mudar a decisão. Cria-se uma nova ADR e a antiga recebe o novo status.
- Cortes de escopo são registrados como nota no fim desta ADR, na seção "Notas de escopo". Regra: ao fim de cada marco, se o estouro acumulado passar de 3 dias úteis, aplica-se o próximo corte previsto na ordem de corte do projeto: M9 (camada 4), depois na camada 3: 8.3 Debezium, 8.2 extração, 8.4 mensageria avançada, 8.1 registry; antes disso, os cortes internos de cada marco anotados nesta ADR. Registra-se a nota aqui.

## Alternativas rejeitadas (e por quê)

- Registrar decisões só em issues ou no histórico de commits: a busca é ruim e a decisão some quando a issue é fechada ou o commit é reescrito.
- Registrar todas as escolhas, inclusive as triviais: o volume cresce e as ADRs úteis se perdem no meio.

## Consequências

- Todo PR que faz uma escolha nova traz a ADR no mesmo PR ou no anterior.
- Revisar uma ADR antiga exige nova ADR, o que deixa a trilha de mudanças visível.

## Prova

- Template em `docs/adr/template.md`.
- Regra de pronto em `CONTRIBUTING.md`, seção "Definição de pronto".

## Notas de escopo

Nenhuma até a data desta ADR.

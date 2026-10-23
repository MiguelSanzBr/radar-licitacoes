# ADR-0006: estratégia de coleta incremental

- Status: proposta
- Data: 2026-10-22

Esta versão traz as seções sobre retomada e idempotência do backfill e sobre janela, sobreposição e cursor do incremental. O restante (redescoberta dos abertos) será completado com os números da sonda.

## Retomada e idempotência

O backfill percorre um dia por vez (janela de 1 dia no fuso America/Sao_Paulo, convertida para `Instant`) e cada janela vira uma `IngestionRun`. A run guarda `cursor_page`, a última página processada por inteiro, e os contadores `fetched`, `discovered`, `updated` e `unchanged`.

- Uma página é processada em uma única transação: os avisos (`Procurement`), os eventos do outbox e o avanço do cursor confirmam juntos. Na última página a mesma transação também marca a run como `DONE`, então nunca sobra uma run em aberto com todas as páginas já confirmadas. Se a transação falha, nada da página fica gravado e o cursor não anda.
- Falha na coleta (`PncpUnavailableException`, `PncpProtocolException`) ou no processamento marca a run como `FAILED` com a mensagem de erro (limitada a 2000 caracteres) e interrompe o intervalo: os dias seguintes não são tentados. O estado gravado é relido antes de marcar a falha, porque a run em memória pode estar à frente do que foi confirmado.
- Rodar de novo o mesmo dia encontra a run em aberto (`RUNNING` ou `FAILED`), volta a `RUNNING` e continua em `cursor_page + 1`, sem pedir de novo as páginas já confirmadas. Um índice único parcial (V8) garante no máximo uma run em aberto por janela.
- Rodar um dia já `DONE` abre uma nova run e percorre a janela inteira. O resultado é idempotente: o hash de conteúdo do `Procurement` faz o aviso sem mudança contar como `unchanged`, só atualiza `last_seen_at` e não gera evento. Dois processamentos do mesmo intervalo não duplicam linhas nem eventos `ProcurementDiscovered`.
- A paginação do PNCP é instável: um registro pode mudar de página enquanto a janela é lida. Numa passada só, um registro que sobe da página 2 para a 1 depois que a 1 foi lida se perde, e outro empurrado da 1 para a 2 aparece duas vezes. A repetição conta como `unchanged`, então não duplica. A perda é pega por uma segunda passada da mesma janela (redescoberta ou sobreposição do incremental), nunca por uma garantia da passada única.
- Concorrência: o caso de uso guarda, em memória, a janela (tipo, modalidade e início) de cada run em andamento. Um segundo pedido para a mesma janela falha com `BackfillWindowBusyException`: o endpoint ops responde 409 e o agendador pula o dia e registra em log. O `@Version` da `ingestion_run` (V9) é a rede de segurança: uma cópia velha da run não sobrescreve um cursor mais novo. A trava é por processo. Com mais de uma instância da aplicação seria preciso um advisory lock do Postgres (não implementado).
- Agendamento: o agendador percorre o intervalo configurado dia a dia, pula os dias que já têm run `DONE`, confere a janela noturna antes de cada dia e para quando ela fecha. O endpoint ops não pula dias `DONE`: repetir o intervalo percorre tudo de novo e é idempotente.

Prova: `BackfillIT` (retomada na página 3, idempotência e paginação instável) e `BackfillUseCaseTest`.

## Janela, sobreposição e cursor

A sonda de 24 horas ainda não terminou, então os valores abaixo são os padrões do plano e **a sonda pode revisá-los**: intervalo, sobreposição e estratégia ficam em propriedades, sem mudança de código.

| Parâmetro | Valor usado | Propriedade |
|---|---|---|
| Estratégia | `/contratacoes/atualizacao` por janela de data de atualização (a confirmar pela S5) | n/a |
| Intervalo do polling | 5 minutos (`fixedDelay`, o próximo ciclo só começa 5 min depois do fim do anterior) | `radar.ingest.incremental.interval` |
| Sobreposição | 15 minutos para trás a partir do watermark | `radar.ingest.incremental.overlap` |
| Olhar para trás sem cursor | 1 dia | `radar.ingest.incremental.initial-lookback` |
| Modalidades | 6 | `radar.ingest.modalities` |
| Fechamento de propostas vencidas | a cada 10 minutos, páginas de 500 | `radar.ingest.close-expired.interval` |

- **Cursor.** `ingest.ingestion_cursor` guarda uma linha por modalidade: `watermark` (maior `dataAtualizacaoGlobal` já processado), `overlap` e `updated_at`. A janela de cada coleta é `[watermark - overlap, agora]`. O watermark nunca anda para trás (`IngestionCursor.advance` recusa um instante anterior) e nunca passa de `agora`: um relógio adiantado do lado do PNCP não pode pular o que ainda falta ler.
- **Datas inteiras.** O endpoint de atualização só aceita datas (`yyyyMMdd`). A janela vira o intervalo de dias de São Paulo que ela toca e todos os itens desses dias são aplicados, inclusive os anteriores ao início da janela. Isso é inofensivo pela idempotência (hash igual conta como `unchanged` e não gera evento) e evita decidir descartar um item por causa de um fuso ainda não confirmado pela sonda. O custo é reler, a cada ciclo, o que o dia já tem. Se a sonda mostrar volume alto, a saída é descartar no cliente os itens anteriores ao início da janela.
- **Decisão sobre reler o dia (aplicar tudo).** Reler o dia inteiro protege contra atraso da listagem maior que a sobreposição (um item que o PNCP só publica na listagem depois de mais de 15 minutos ainda é pego enquanto o dia estiver na janela). Por isso fica valendo aplicar tudo por ora. Descartar no cliente os itens anteriores ao início da janela menos uma margem de segurança será decidido depois das sondas S5 e S7, que medem o fuso e o volume.
- **Janela vazia e crescimento do intervalo de datas.** Sem itens novos o watermark não anda, então o início da janela (`watermark - overlap`) fica parado enquanto o fim é sempre `agora`: o intervalo de datas cresce com os dias sem atualização e cada ciclo relê tudo desde o último item. A revisão desse comportamento (por exemplo, andar o watermark até `agora - overlap` quando a listagem responde vazia) fica para depois da sonda.
- **Relógio para trás.** O watermark gravado é `max(watermark atual, min(maior atualização vista, agora))`: um relógio que volta não recua o cursor nem faz o avanço falhar.
- **Sucesso e falha.** Cada coleta é uma `IngestionRun` do tipo `INCREMENTAL`, com a janela em `window_start` e `window_end`, e cada página confirma numa transação (a mesma do backfill, num colaborador compartilhado). O watermark só avança depois da última página confirmada, para o maior `pncpUpdatedAt` visto (ou fica onde estava sem itens). Se qualquer página falha, a run vira `FAILED`, o cursor não anda e o próximo ciclo lê a mesma janela de novo, que a idempotência absorve. Uma queda entre a última página e a gravação do cursor tem o mesmo efeito.
- **Sobreposição não duplica evento.** Um item que aparece em duas janelas seguidas chega com o mesmo conteúdo: o hash é igual e nenhum evento sai. Só um item cujo hash mudou emite `ProcurementUpdated` (e `ProcurementSituationChanged` se a situação mudou), uma vez, mesmo se a janela seguinte o trouxer de novo.
- **Fechamento.** `CloseExpiredUseCase` lê, pelo índice parcial de `proposal_closing_at`, os avisos `PUBLISHED` sem `closed_emitted_at` e com encerramento vencido, em páginas de 500, e chama `closeIfExpired`. Salvar o aviso e registrar `ProcurementClosed` no outbox acontecem numa transação por página. Como o aviso fechado sai da consulta, rodar de novo (ou duas instâncias ao mesmo tempo) não emite nada a mais.
- **Interruptores.** O liga/desliga são as flags `INCREMENTAL_SYNC` e `CLOSE_EXPIRED` (Togglz, ADR-0021). O estado inicial de cada uma é a propriedade `radar.ingest.incremental.enabled` e `radar.ingest.close-expired.enabled`: ligadas no perfil padrão da aplicação e desligadas nos testes. O agendador lê a flag a cada execução, então desligar em `/ops/togglz` vale a partir da próxima.
- **Métrica.** O contador `ingest_items_total{kind, outcome}` (`kind` = `backfill` ou `incremental`; `outcome` = `discovered`, `updated` ou `unchanged`) é incrementado pelo colaborador de página depois da confirmação, então uma página desfeita não conta.
- **Limites conhecidos.** Não há trava entre instâncias: duas coletas da mesma modalidade ao mesmo tempo só gastam leitura, porque a idempotência e o `@Version` protegem os dados. Um advisory lock do Postgres resolveria e não foi implementado.

Prova: `IngestionCursorTest`, `IncrementalSyncUseCaseTest`, `CloseExpiredUseCaseTest` e `IncrementalSyncIT` (sobreposição sem duplicar, item alterado, fechamento três vezes e contadores).

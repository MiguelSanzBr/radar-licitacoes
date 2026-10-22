# ADR-0006: estratégia de coleta incremental

- Status: proposta
- Data: 2026-10-22

Esta versão traz só a seção sobre retomada e idempotência do backfill. O restante (estratégia incremental, janela de sobreposição, redescoberta) será completado com os números da sonda.

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

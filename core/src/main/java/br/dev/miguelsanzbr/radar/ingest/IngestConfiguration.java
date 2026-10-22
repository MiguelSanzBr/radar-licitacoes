package br.dev.miguelsanzbr.radar.ingest;

import br.dev.miguelsanzbr.radar.ingest.adapter.in.ops.BackfillProperties;
import br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler.IngestScheduleProperties;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowPolicy;
import br.dev.miguelsanzbr.radar.ingest.application.CloseExpiredUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.PageProcessor;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestMetrics;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionCursorRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpGateway;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/** Wires the ingest use cases, which stay free of Spring stereotypes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BackfillProperties.class, IngestScheduleProperties.class})
class IngestConfiguration {

  private static final int CLOSE_PAGE_SIZE = 500;

  @Bean
  BackfillWindowPolicy backfillWindowPolicy(BackfillProperties properties) {
    return new BackfillWindowPolicy(
        properties.allowedFrom(), properties.allowedTo(), ZoneId.of("America/Sao_Paulo"));
  }

  @Bean
  PageProcessor pageProcessor(
      ProcurementRepository procurements,
      IngestionRunRepository runs,
      OutboxAppender outbox,
      TransactionOperations transactions,
      IngestMetrics metrics,
      Clock clock) {
    return new PageProcessor(procurements, runs, outbox, transactions, metrics, clock);
  }

  @Bean
  BackfillUseCase backfillUseCase(
      PncpGateway gateway, IngestionRunRepository runs, PageProcessor pages, Clock clock) {
    return new BackfillUseCase(gateway, runs, pages, clock);
  }

  @Bean
  IncrementalSyncUseCase incrementalSyncUseCase(
      PncpGateway gateway,
      IngestionCursorRepository cursors,
      IngestionRunRepository runs,
      PageProcessor pages,
      Clock clock,
      IngestScheduleProperties properties) {
    return new IncrementalSyncUseCase(
        gateway,
        cursors,
        runs,
        pages,
        clock,
        properties.incremental().initialLookback(),
        properties.incremental().overlap());
  }

  @Bean
  CloseExpiredUseCase closeExpiredUseCase(
      ProcurementRepository procurements,
      OutboxAppender outbox,
      TransactionOperations transactions,
      Clock clock) {
    return new CloseExpiredUseCase(procurements, outbox, transactions, clock, CLOSE_PAGE_SIZE);
  }
}

package br.dev.miguelsanzbr.radar.ingest;

import br.dev.miguelsanzbr.radar.ingest.adapter.in.ops.BackfillProperties;
import br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler.IngestScheduleProperties;
import br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler.PartitionProperties;
import br.dev.miguelsanzbr.radar.ingest.adapter.in.scheduler.ValueCentsBackfillProperties;
import br.dev.miguelsanzbr.radar.ingest.adapter.out.pncp.PncpProperties;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowPolicy;
import br.dev.miguelsanzbr.radar.ingest.application.CloseExpiredUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.EstimatedValueCentsBackfill;
import br.dev.miguelsanzbr.radar.ingest.application.IncrementalSyncUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.PageFetcher;
import br.dev.miguelsanzbr.radar.ingest.application.PageProcessor;
import br.dev.miguelsanzbr.radar.ingest.application.PartitionMaintenance;
import br.dev.miguelsanzbr.radar.ingest.application.port.EstimatedValueCentsStore;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestMetrics;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionCursorRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.OutboxAppender;
import br.dev.miguelsanzbr.radar.ingest.application.port.PartitionCatalog;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpGateway;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.ValueCentsMetrics;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionOperations;

/** Wires the ingest use cases, which stay free of Spring stereotypes. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
  BackfillProperties.class,
  IngestScheduleProperties.class,
  PartitionProperties.class,
  ValueCentsBackfillProperties.class,
  IngestFetchProperties.class
})
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

  /**
   * Pages are fetched ahead on virtual threads, {@code radar.ingest.fetch-ahead} of them (never
   * more than the PNCP bulkhead lets through at once). The rate limiter, not this number, caps
   * requests per second.
   */
  @Bean
  PageFetcher pageFetcher(PncpProperties pncp, IngestFetchProperties fetch) {
    return PageFetcher.virtualThreads(
        IngestFetchProperties.effective(fetch.fetchAhead(), pncp.maxConcurrentCalls()));
  }

  @Bean
  BackfillUseCase backfillUseCase(
      PncpGateway gateway,
      IngestionRunRepository runs,
      PageProcessor pages,
      PageFetcher fetcher,
      Clock clock) {
    return new BackfillUseCase(gateway, runs, pages, fetcher, clock);
  }

  @Bean
  IncrementalSyncUseCase incrementalSyncUseCase(
      PncpGateway gateway,
      IngestionCursorRepository cursors,
      IngestionRunRepository runs,
      PageProcessor pages,
      PageFetcher fetcher,
      Clock clock,
      IngestScheduleProperties properties) {
    return new IncrementalSyncUseCase(
        gateway,
        cursors,
        runs,
        pages,
        fetcher,
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

  @Bean
  PartitionMaintenance partitionMaintenance(
      PartitionCatalog catalog, Clock clock, PartitionProperties properties) {
    return new PartitionMaintenance(catalog, clock, properties.monthsAhead());
  }

  @Bean
  EstimatedValueCentsBackfill estimatedValueCentsBackfill(
      EstimatedValueCentsStore store,
      ValueCentsMetrics metrics,
      ValueCentsBackfillProperties properties) {
    return new EstimatedValueCentsBackfill(store, metrics, properties.batchSize());
  }
}

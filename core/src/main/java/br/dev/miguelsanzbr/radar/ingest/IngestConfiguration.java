package br.dev.miguelsanzbr.radar.ingest;

import br.dev.miguelsanzbr.radar.ingest.adapter.in.ops.BackfillProperties;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillUseCase;
import br.dev.miguelsanzbr.radar.ingest.application.BackfillWindowPolicy;
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
@EnableConfigurationProperties(BackfillProperties.class)
class IngestConfiguration {

  @Bean
  BackfillWindowPolicy backfillWindowPolicy(BackfillProperties properties) {
    return new BackfillWindowPolicy(
        properties.allowedFrom(), properties.allowedTo(), ZoneId.of("America/Sao_Paulo"));
  }

  @Bean
  BackfillUseCase backfillUseCase(
      PncpGateway gateway,
      ProcurementRepository procurements,
      IngestionRunRepository runs,
      OutboxAppender outbox,
      TransactionOperations transactions,
      Clock clock) {
    return new BackfillUseCase(gateway, procurements, runs, outbox, transactions, clock);
  }
}

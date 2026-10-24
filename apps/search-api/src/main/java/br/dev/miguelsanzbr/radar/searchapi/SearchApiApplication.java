package br.dev.miguelsanzbr.radar.searchapi;

import br.dev.miguelsanzbr.radar.PrimaryPersistenceConfiguration;
import br.dev.miguelsanzbr.radar.RadarConfiguration;
import br.dev.miguelsanzbr.radar.RedisClientConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/**
 * The read side of the platform (ADR-0028): search, alerts, identity and operations. Nothing of
 * ingest is in the context. The outbox package is scanned because alerts append to the outbox and
 * the operations panel reads its gauges; the relay stays off here (relay-enabled=false), so only
 * the ingestor publishes. It runs no migrations.
 */
@SpringBootApplication(
    scanBasePackages = {
      "br.dev.miguelsanzbr.radar.searchapi",
      "br.dev.miguelsanzbr.radar.search",
      "br.dev.miguelsanzbr.radar.alerts",
      "br.dev.miguelsanzbr.radar.identity",
      "br.dev.miguelsanzbr.radar.ops",
      "br.dev.miguelsanzbr.radar.outbox",
      "br.dev.miguelsanzbr.radar.observability"
    })
@Import({
  RadarConfiguration.class,
  PrimaryPersistenceConfiguration.class,
  RedisClientConfiguration.class
})
public class SearchApiApplication {

  public static void main(String[] args) {
    // why: application-search-api.properties overlays the defaults of core (see
    // IngestorApplication)
    new SpringApplicationBuilder(SearchApiApplication.class).profiles("search-api").run(args);
  }
}

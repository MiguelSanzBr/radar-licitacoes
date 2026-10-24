package br.dev.miguelsanzbr.radar.ingestor;

import br.dev.miguelsanzbr.radar.PrimaryPersistenceConfiguration;
import br.dev.miguelsanzbr.radar.RadarConfiguration;
import br.dev.miguelsanzbr.radar.identity.SecurityConfig;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/**
 * The write side of the platform (ADR-0028): collects from the PNCP and relays the outbox. It scans
 * the packages of its own modules instead of the whole root, so nothing of search or alerts is in
 * the context. The security chains come from identity (the operator routes need them) and the JPA
 * entities are those of ingest. It owns the Flyway migrations.
 */
@SpringBootApplication(
    scanBasePackages = {
      "br.dev.miguelsanzbr.radar.ingestor",
      "br.dev.miguelsanzbr.radar.ingest",
      "br.dev.miguelsanzbr.radar.outbox",
      "br.dev.miguelsanzbr.radar.ops",
      "br.dev.miguelsanzbr.radar.observability"
    })
@AutoConfigurationPackage(basePackages = "br.dev.miguelsanzbr.radar.ingest")
@Import({RadarConfiguration.class, PrimaryPersistenceConfiguration.class, SecurityConfig.class})
public class IngestorApplication {

  public static void main(String[] args) {
    // why: application-ingestor.properties overlays the defaults of core, which a file of the same
    // name as core's application.properties would hide
    new SpringApplicationBuilder(IngestorApplication.class).profiles("ingestor").run(args);
  }
}

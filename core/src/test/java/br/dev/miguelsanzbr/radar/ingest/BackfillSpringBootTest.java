package br.dev.miguelsanzbr.radar.ingest;

import br.dev.miguelsanzbr.radar.outbox.OutboxSpringBootTest;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Full context on the shared Postgres, the relay off, the PNCP client pointed at {@link
 * BackfillTestSupport#PNCP} with a fast rate limit and a two attempt retry.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@OutboxSpringBootTest
@AutoConfigureMockMvc
@Import(BackfillTestConfig.class)
@TestPropertySource(
    properties = {
      "radar.pncp.requests-per-second=1000",
      "radar.ingest.fetch-ahead=4",
      "radar.pncp.retry.max-attempts=2",
      "radar.pncp.retry.initial-backoff=10ms"
    })
public @interface BackfillSpringBootTest {}

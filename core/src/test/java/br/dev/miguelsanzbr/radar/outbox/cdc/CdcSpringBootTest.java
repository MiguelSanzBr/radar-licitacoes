package br.dev.miguelsanzbr.radar.outbox.cdc;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full context on the dedicated Postgres and Kafka of {@link CdcInfra}. The scheduler is off: the
 * tests call the relay themselves, or leave publishing to Debezium.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(
    properties = {
      "radar.outbox.relay-enabled=false",
      "radar.outbox.publish-timeout=5s",
      // why: no collector in tests; W3C propagation is only on when tracing export is switched on
      "management.tracing.export.otlp.enabled=false"
    })
@AutoConfigureTracing
@Import(CdcInfraTestConfig.class)
public @interface CdcSpringBootTest {}

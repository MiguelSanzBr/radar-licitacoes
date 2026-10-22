package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Full context on the shared Postgres and the shared Kafka. The scheduler is off, tests drive the
 * relay themselves, and a publish waits at most one second for the ack, so an outage shows up as
 * failed publishes quickly.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(
    properties = {"radar.outbox.relay-enabled=false", "radar.outbox.publish-timeout=1s"})
@Import({PostgresTestConfig.class, KafkaTestConfig.class})
public @interface KafkaSpringBootTest {}

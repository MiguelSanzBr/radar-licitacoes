package br.dev.miguelsanzbr.radar.outbox;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Full context on the shared Postgres with the in-memory publisher and the scheduler off. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@SpringBootTest(
    properties = {"radar.outbox.relay-enabled=false", "radar.outbox.publisher=inmemory"})
@Import({PostgresTestConfig.class, OutboxTestConfig.class})
public @interface OutboxSpringBootTest {}

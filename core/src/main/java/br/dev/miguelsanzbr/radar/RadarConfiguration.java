package br.dev.miguelsanzbr.radar;

import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Application-wide infrastructure beans shared by every context; public so the apps import it
 * (ADR-0028).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class RadarConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * A pool of its own instead of Boot's virtual-thread scheduler, which runs every fixed-delay task
   * on one platform thread: a slow task would then starve the outbox relay. Tasks that block stay
   * on their own pool thread; Tomcat and the Kafka listeners keep using virtual threads.
   */
  @Bean
  ThreadPoolTaskScheduler taskScheduler(@Value("${spring.task.scheduling.pool.size:4}") int size) {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(size);
    scheduler.setThreadNamePrefix("radar-sched-");
    return scheduler;
  }

  @Bean
  UuidV7 uuidV7(Clock clock) {
    return new UuidV7(clock, new SecureRandom());
  }
}

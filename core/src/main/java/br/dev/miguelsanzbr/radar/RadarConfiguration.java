package br.dev.miguelsanzbr.radar;

import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Application-wide infrastructure beans shared by every context. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RadarConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  UuidV7 uuidV7(Clock clock) {
    return new UuidV7(clock, new SecureRandom());
  }
}

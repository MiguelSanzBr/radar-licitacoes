package br.dev.miguelsanzbr.radar.identity.adapter.config;

import br.dev.miguelsanzbr.radar.identity.application.ManageApiKeysUseCase;
import br.dev.miguelsanzbr.radar.identity.application.port.ApiKeys;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the plain use cases of the identity context; the application layer has no Spring types. */
@Configuration(proxyBeanMethods = false)
class IdentityConfiguration {

  @Bean
  ManageApiKeysUseCase manageApiKeysUseCase(
      ApiKeys keys,
      @Value("${radar.api-keys.max-per-subject:5}") int maxPerSubject,
      Clock clock,
      UuidV7 ids) {
    return new ManageApiKeysUseCase(keys, maxPerSubject, clock, ids, new SecureRandom());
  }
}

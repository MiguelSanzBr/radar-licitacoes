package br.dev.miguelsanzbr.radar.alerts.adapter.config;

import br.dev.miguelsanzbr.radar.alerts.application.EraseSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.IdempotencyGuard;
import br.dev.miguelsanzbr.radar.alerts.application.ManageSavedSearchUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.MatchProcurementUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.PurgeExpiredUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.RegisterSubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.VerifySubscriberUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.port.AlertDeliveries;
import br.dev.miguelsanzbr.radar.alerts.application.port.AlertEvents;
import br.dev.miguelsanzbr.radar.alerts.application.port.IdempotencyStore;
import br.dev.miguelsanzbr.radar.alerts.application.port.MatcherMetrics;
import br.dev.miguelsanzbr.radar.alerts.application.port.ProcessedEvents;
import br.dev.miguelsanzbr.radar.alerts.application.port.ReverseSearch;
import br.dev.miguelsanzbr.radar.alerts.application.port.SavedSearchRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.SubscriberRepository;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationMailer;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationTokenStore;
import br.dev.miguelsanzbr.radar.alerts.domain.SavedSearchLimit;
import br.dev.miguelsanzbr.radar.shared.UuidV7;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the plain use cases of the alerts context; the application layer has no Spring types. */
@Configuration(proxyBeanMethods = false)
class AlertsConfig {

  @Bean
  SavedSearchLimit savedSearchLimit(
      @Value("${radar.alerts.max-saved-searches:20}") int maxSavedSearches) {
    return new SavedSearchLimit(maxSavedSearches);
  }

  @Bean
  RegisterSubscriberUseCase registerSubscriberUseCase(
      SubscriberRepository subscribers,
      VerificationTokenStore tokens,
      VerificationMailer mailer,
      UnitOfWork unitOfWork,
      Clock clock,
      UuidV7 ids) {
    return new RegisterSubscriberUseCase(
        subscribers, tokens, mailer, unitOfWork, clock, ids, new SecureRandom());
  }

  @Bean
  VerifySubscriberUseCase verifySubscriberUseCase(
      SubscriberRepository subscribers,
      VerificationTokenStore tokens,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new VerifySubscriberUseCase(subscribers, tokens, unitOfWork, clock);
  }

  @Bean
  EraseSubscriberUseCase eraseSubscriberUseCase(
      SubscriberRepository subscribers,
      SavedSearchRepository searches,
      IdempotencyStore idempotency,
      VerificationTokenStore tokens,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new EraseSubscriberUseCase(
        subscribers, searches, idempotency, tokens, unitOfWork, clock);
  }

  @Bean
  ManageSavedSearchUseCase manageSavedSearchUseCase(
      SubscriberRepository subscribers,
      SavedSearchRepository searches,
      SavedSearchLimit limit,
      UnitOfWork unitOfWork,
      Clock clock,
      UuidV7 ids) {
    return new ManageSavedSearchUseCase(subscribers, searches, limit, unitOfWork, clock, ids);
  }

  @Bean
  IdempotencyGuard idempotencyGuard(IdempotencyStore store, UnitOfWork unitOfWork, Clock clock) {
    return new IdempotencyGuard(store, unitOfWork, clock);
  }

  @Bean
  PurgeExpiredUseCase purgeExpiredUseCase(
      SubscriberRepository subscribers,
      IdempotencyStore idempotency,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new PurgeExpiredUseCase(subscribers, idempotency, unitOfWork, clock);
  }

  @Bean
  MatchProcurementUseCase matchProcurementUseCase(
      ReverseSearch search,
      AlertDeliveries deliveries,
      ProcessedEvents inbox,
      AlertEvents events,
      MatcherMetrics metrics,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new MatchProcurementUseCase(
        search, deliveries, inbox, events, metrics, unitOfWork, clock);
  }
}

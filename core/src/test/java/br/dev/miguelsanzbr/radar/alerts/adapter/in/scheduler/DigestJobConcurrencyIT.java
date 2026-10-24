package br.dev.miguelsanzbr.radar.alerts.adapter.in.scheduler;

import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.delivery;
import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.namedSearch;
import static br.dev.miguelsanzbr.radar.alerts.adapter.MatcherData.subscriber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.miguelsanzbr.radar.alerts.Facts;
import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.alerts.application.SendDigestUseCase;
import br.dev.miguelsanzbr.radar.alerts.application.port.AlertMailer;
import br.dev.miguelsanzbr.radar.alerts.application.port.ClusterLock;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog;
import br.dev.miguelsanzbr.radar.alerts.application.port.DeliveryLog.Queued;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.Frequency;
import br.dev.miguelsanzbr.radar.alerts.domain.SearchMatch;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Two instances reaching the digest hour together: the subscriber gets one mail, not two. */
class DigestJobConcurrencyIT extends PostgresIT {

  @Autowired DeliveryLog log;
  @Autowired UnitOfWork unitOfWork;
  @Autowired ClusterLock lock;

  @Test
  void twoConcurrentRunsSendTheDigestOnce() {
    var search = namedSearch(jdbc, subscriber(jdbc), "Notebooks", "DAILY");
    keep(search, "83021808000182-1-000001/2026");
    keep(search, "83021808000182-1-000002/2026");
    var mails = new AtomicInteger();
    AlertMailer mailer =
        new AlertMailer() {
          @Override
          public void sendInstant(String to, String searchName, AlertMatched alert) {}

          @Override
          public void sendDigest(String to, List<Queued> items) {
            if (mails.incrementAndGet() == 1) {
              // the other instance starts its run while this one is still inside the transaction
              CompletableFuture.runAsync(() -> job(this).send()).join();
            }
          }
        };

    job(mailer).send();

    assertThat(mails).hasValue(1);
    assertThat(log.unsentDigest()).isEmpty();
  }

  @Test
  void aCrashAfterTheFirstSubscriberKeepsThatSubscriberMarkedSent() {
    keep(namedSearch(jdbc, subscriber(jdbc), "Notebooks", "DAILY"), "83021808000182-1-000001/2026");
    keep(namedSearch(jdbc, subscriber(jdbc), "Cadeiras", "DAILY"), "83021808000182-1-000002/2026");
    var mails = new AtomicInteger();
    AlertMailer crashingOnTheSecond =
        new AlertMailer() {
          @Override
          public void sendInstant(String to, String searchName, AlertMatched alert) {}

          @Override
          public void sendDigest(String to, List<Queued> items) {
            if (mails.incrementAndGet() == 2) {
              throw new IllegalStateException("process dies here");
            }
          }
        };

    assertThatThrownBy(() -> job(crashingOnTheSecond).send())
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            jdbc.sql("SELECT count(*) FROM alerts.alert_delivery WHERE sent_at IS NOT NULL")
                .query(Long.class)
                .single())
        .isEqualTo(1);
    assertThat(log.unsentDigest()).hasSize(1);
  }

  private DigestJob job(AlertMailer mailer) {
    var useCase = new SendDigestUseCase(log, mailer, unitOfWork, Clock.systemUTC());
    return new DigestJob(useCase, flag -> true, lock);
  }

  private void keep(UUID search, String procurement) {
    delivery(jdbc, search, procurement, Instant.now().minusSeconds(5));
    log.keepForDigest(
        AlertMatched.of(
            new SearchMatch(search, UUID.randomUUID(), Frequency.DAILY),
            Facts.procurement().id(procurement).build()));
  }
}

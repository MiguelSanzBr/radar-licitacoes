package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.discovered;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.id;
import static br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa.ProcurementFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.alerts.adapter.PostgresIT;
import br.dev.miguelsanzbr.radar.ingest.application.port.ProcurementRepository;
import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.shared.Money;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionOperations;
import org.togglz.core.Feature;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.repository.FeatureState;

/** The switch as the application wires it: the operator flips READ_VALUE_CENTS at runtime. */
class ReadValueCentsFlagIT extends PostgresIT {

  @Autowired ProcurementRepository repository;
  @Autowired FeatureManager manager;
  @Autowired TransactionOperations transactions;

  @AfterEach
  void cleanUp() {
    jdbc.sql("TRUNCATE ops.togglz_feature").update();
    jdbc.sql(
            "DELETE FROM ingest.procurement_situation_history WHERE numero_controle_pncp LIKE"
                + " '83021808000182-1-0009%'")
        .update();
    jdbc.sql(
            "DELETE FROM ingest.procurement WHERE numero_controle_pncp LIKE '83021808000182-1-0009%'")
        .update();
  }

  private void flip(boolean on) {
    Feature feature =
        manager.getFeatures().stream()
            .filter(f -> f.name().equals(Flag.READ_VALUE_CENTS.name()))
            .findFirst()
            .orElseThrow();
    manager.setFeatureState(new FeatureState(feature, on));
  }

  @Test
  void theFlagStartsOffSoTheNumericColumnIsRead() {
    repository.save(discovered(snapshot(id(900))));

    assertThat(repository.findById(id(900)).orElseThrow().estimatedValue())
        .isEqualTo(Money.of("1500.50"));
  }

  @Test
  void flippingTheFlagMovesTheReadsToTheCentsAndBackWithoutARestart() {
    repository.save(discovered(snapshot(id(901))));
    // make the two columns differ (the trigger would not let a writer): a row of a bad backfill
    transactions.executeWithoutResult(
        status -> {
          jdbc.sql("SET LOCAL session_replication_role = replica").update();
          jdbc.sql(
                  "UPDATE ingest.procurement SET estimated_value_cents = 99999 WHERE"
                      + " numero_controle_pncp = :n")
              .param("n", id(901).value())
              .update();
        });

    assertThat(repository.findById(id(901)).orElseThrow().estimatedValue())
        .isEqualTo(Money.of("1500.50"));
    flip(true);
    assertThat(repository.findById(id(901)).orElseThrow().estimatedValue())
        .isEqualTo(Money.of(99999));
    flip(false);
    assertThat(repository.findById(id(901)).orElseThrow().estimatedValue())
        .isEqualTo(Money.of("1500.50"));
  }
}

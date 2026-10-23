package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The expand step (V14): both value columns stay in step whichever one a writer touches. */
@PostgresJpaTest
class EstimatedValueTriggerIT {

  @Autowired JdbcTemplate jdbc;

  private void insert(String table, String number, String publishedAt, String value, Long cents) {
    jdbc.update(
        """
        insert into %s (numero_controle_pncp, published_at, content_hash, pncp_situation_id,
          pncp_situation_name, status, object_description, organ_cnpj, organ_name, modality_id,
          modality_name, pncp_updated_at, raw, first_seen_at, last_seen_at, estimated_value,
          estimated_value_cents)
        values (?, ?::timestamptz, '\\x00', 1, 's', 'PUBLISHED', 'o', '83021808000182',
          'n', 6, 'm', now(), '{}', now(), now(), ?::numeric, ?)
        """
            .formatted(table),
        number,
        publishedAt,
        value,
        cents);
  }

  private void insert(String number, String value, Long cents) {
    insert("ingest.procurement", number, "2026-10-21T12:00:00Z", value, cents);
  }

  private BigDecimal value(String number) {
    return jdbc.queryForObject(
        "select estimated_value from ingest.procurement where numero_controle_pncp = ?",
        BigDecimal.class,
        number);
  }

  private Long cents(String number) {
    return jdbc.queryForObject(
        "select estimated_value_cents from ingest.procurement where numero_controle_pncp = ?",
        Long.class,
        number);
  }

  private void update(String set, String number) {
    jdbc.update("update ingest.procurement set " + set + " where numero_controle_pncp = ?", number);
  }

  @Test
  void insertOfTheValueAloneFillsTheCents() {
    insert("a", "1500.50", null);

    assertThat(cents("a")).isEqualTo(150050L);
  }

  @Test
  void insertOfTheCentsAloneFillsTheValue() {
    insert("b", null, 150050L);

    assertThat(value("b")).isEqualByComparingTo("1500.50");
  }

  @Test
  void insertWithNeitherLeavesBothNull() {
    insert("c", null, null);

    assertThat(value("c")).isNull();
    assertThat(cents("c")).isNull();
  }

  @Test
  void insertOfAgreeingValuesIsAccepted() {
    insert("d", "10.00", 1000L);

    assertThat(cents("d")).isEqualTo(1000L);
  }

  @Test
  void insertOfDisagreeingValuesFails() {
    assertThatThrownBy(() -> insert("e", "10.00", 1001L))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("disagree");
  }

  @Test
  void theValueIsRoundedToTwoDecimalsBeforeTheCentsAreDerived() {
    insert("f", "12.345", null);

    assertThat(cents("f")).isEqualTo(1235L);
  }

  @Test
  void updateOfTheValueAloneMovesTheCents() {
    insert("g", "1.00", null);

    update("estimated_value = 2.50", "g");

    assertThat(cents("g")).isEqualTo(250L);
  }

  @Test
  void updateOfTheValueToNullClearsTheCents() {
    insert("h", "1.00", null);

    update("estimated_value = null", "h");

    assertThat(cents("h")).isNull();
  }

  @Test
  void updateOfTheCentsAloneMovesTheValue() {
    insert("i", "1.00", null);

    update("estimated_value_cents = 777", "i");

    assertThat(value("i")).isEqualByComparingTo("7.77");
  }

  @Test
  void updateOfTheCentsToNullAloneClearsTheValue() {
    insert("n", "1.00", null);

    update("estimated_value_cents = null", "n");

    assertThat(value("n")).isNull();
  }

  @Test
  void updateOfBothAgreeingIsAccepted() {
    insert("j", "1.00", null);

    update("estimated_value = 3.00, estimated_value_cents = 300", "j");

    assertThat(cents("j")).isEqualTo(300L);
  }

  @Test
  void updateOfBothToNullIsAccepted() {
    insert("k", "1.00", null);

    update("estimated_value = null, estimated_value_cents = null", "k");

    assertThat(value("k")).isNull();
    assertThat(cents("k")).isNull();
  }

  @Test
  void updateOfBothDisagreeingFails() {
    insert("l", "1.00", null);

    assertThatThrownBy(() -> update("estimated_value = 3.00, estimated_value_cents = 301", "l"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("disagree");
  }

  @Test
  void aWriterThatTouchesOtherColumnsOnlyHealsARowThatHasNoCentsYet() {
    insert("m", "4.00", null);
    // a row of before the expand step: the cents are still null
    jdbc.execute("set local session_replication_role = replica");
    update("estimated_value_cents = null", "m");
    jdbc.execute("set local session_replication_role = origin");

    update("object_description = 'changed'", "m");

    assertThat(cents("m")).isEqualTo(400L);
  }

  @Test
  void aRowInsertedStraightIntoAPartitionIsCoveredToo() {
    insert("ingest.procurement_y2026m10", "p", "2026-10-02T00:00:00Z", "9.99", null);

    assertThat(cents("p")).isEqualTo(999L);
  }

  @Test
  void aPartitionCreatedLaterGetsTheTriggerToo() {
    jdbc.queryForObject("select ingest.ensure_partition(date '2032-03-01')", String.class);

    insert("ingest.procurement_y2032m03", "q", "2032-03-02T00:00:00Z", "9.99", null);

    assertThat(cents("q")).isEqualTo(999L);
  }

  @Test
  void negativeValuesAndCentsAreDerivedBothWays() {
    insert("neg1", "-12.34", null);
    insert("neg2", null, -999L);

    assertThat(cents("neg1")).isEqualTo(-1234L);
    assertThat(value("neg2")).isEqualByComparingTo("-9.99");
  }

  @Test
  void centsThatOverflowTheNumericColumnFailInsteadOfWrapping() {
    insert("big", "1.00", null);

    assertThatThrownBy(() -> update("estimated_value_cents = 9223372036854775807", "big"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void theLargestValueThatFitsTheNumericColumnFitsTheCentsToo() {
    insert("max", "9999999999999999.99", null);

    assertThat(cents("max")).isEqualTo(999999999999999999L);
  }

  @Test
  void anUpdateThatRewritesTheSameValuesChangesNothing() {
    insert("same", "7.00", null);

    update("estimated_value = 7.00, estimated_value_cents = 700", "same");

    assertThat(value("same")).isEqualByComparingTo("7.00");
    assertThat(cents("same")).isEqualTo(700L);
  }
}

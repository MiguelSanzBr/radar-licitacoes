package br.dev.miguelsanzbr.radar.ingest.adapter.out.jpa;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class JpaProcurementRepositoryTest {

  @Test
  void sqlState23505IsAUniqueViolation() {
    var duplicate = new DataIntegrityViolationException("dup", new SQLException("dup", "23505"));

    assertThat(JpaProcurementRepository.isUniqueViolation(duplicate)).isTrue();
  }

  @Test
  void otherSqlStatesAreNot() {
    var notNull = new DataIntegrityViolationException("null", new SQLException("null", "23502"));

    assertThat(JpaProcurementRepository.isUniqueViolation(notNull)).isFalse();
  }

  @Test
  void aCauseThatIsNotSqlIsNot() {
    assertThat(JpaProcurementRepository.isUniqueViolation(new DataIntegrityViolationException("x")))
        .isFalse();
  }
}

package br.dev.miguelsanzbr.radar.ingest.application.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PncpGatewayTest {

  @Test
  void aGatewayThatKnowsNoBlockIsNeverBlocked() {
    PncpGateway gateway =
        new PncpGateway() {
          @Override
          public PncpPage publications(LocalDate from, LocalDate to, int modalityId, int page) {
            throw new UnsupportedOperationException();
          }

          @Override
          public PncpPage updates(LocalDate from, LocalDate to, int modalityId, int page) {
            throw new UnsupportedOperationException();
          }
        };

    assertThat(gateway.blockedUntil()).isEmpty();
  }
}

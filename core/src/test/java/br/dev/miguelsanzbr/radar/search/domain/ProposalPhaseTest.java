package br.dev.miguelsanzbr.radar.search.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProposalPhaseTest {

  private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");
  private static final Instant PAST = NOW.minusSeconds(3600);
  private static final Instant FUTURE = NOW.plusSeconds(3600);

  @Test
  void aWindowWithNoBoundIsUnknown() {
    assertThat(ProposalPhase.at(null, null, NOW)).isEqualTo(ProposalPhase.UNKNOWN);
  }

  @Test
  void beforeTheOpeningIsUpcoming() {
    assertThat(ProposalPhase.at(FUTURE, null, NOW)).isEqualTo(ProposalPhase.UPCOMING);
    assertThat(ProposalPhase.at(FUTURE, FUTURE.plusSeconds(60), NOW))
        .isEqualTo(ProposalPhase.UPCOMING);
  }

  @Test
  void betweenTheBoundsIsOpen() {
    assertThat(ProposalPhase.at(PAST, FUTURE, NOW)).isEqualTo(ProposalPhase.OPEN);
    assertThat(ProposalPhase.at(null, FUTURE, NOW)).isEqualTo(ProposalPhase.OPEN);
    assertThat(ProposalPhase.at(NOW, FUTURE, NOW)).isEqualTo(ProposalPhase.OPEN);
  }

  @Test
  void withoutAClosingDateAnOpenedWindowIsUnknownLikeTheOpenFilter() {
    assertThat(ProposalPhase.at(PAST, null, NOW)).isEqualTo(ProposalPhase.UNKNOWN);
    assertThat(ProposalPhase.at(NOW, null, NOW)).isEqualTo(ProposalPhase.UNKNOWN);
  }

  @Test
  void fromTheClosingOnIsClosed() {
    assertThat(ProposalPhase.at(PAST, NOW, NOW)).isEqualTo(ProposalPhase.CLOSED);
    assertThat(ProposalPhase.at(null, PAST, NOW)).isEqualTo(ProposalPhase.CLOSED);
  }
}

package br.dev.miguelsanzbr.radar.ingest.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementStatus.Annulled;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementStatus.Published;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementStatus.Revoked;
import br.dev.miguelsanzbr.radar.ingest.domain.ProcurementStatus.Suspended;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProcurementStatusTest {

  private static final ProcurementStatus PUBLISHED = new Published();
  private static final ProcurementStatus SUSPENDED = new Suspended();
  private static final ProcurementStatus REVOKED = new Revoked();
  private static final ProcurementStatus ANNULLED = new Annulled();

  @Test
  void mapsPncpSituationIds() {
    assertThat(ProcurementStatus.fromSituationId(1)).isEqualTo(PUBLISHED);
    assertThat(ProcurementStatus.fromSituationId(2)).isEqualTo(REVOKED);
    assertThat(ProcurementStatus.fromSituationId(3)).isEqualTo(ANNULLED);
    assertThat(ProcurementStatus.fromSituationId(4)).isEqualTo(SUSPENDED);
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 5, 99})
  void unknownIdIsRejected(int id) {
    assertThatIllegalArgumentException().isThrownBy(() -> ProcurementStatus.fromSituationId(id));
  }

  @Test
  void nameIsTheTypeName() {
    assertThat(PUBLISHED.name()).isEqualTo("Published");
    assertThat(SUSPENDED.name()).isEqualTo("Suspended");
    assertThat(REVOKED.name()).isEqualTo("Revoked");
    assertThat(ANNULLED.name()).isEqualTo("Annulled");
  }

  static Stream<Arguments> transitions() {
    var all = new ProcurementStatus[] {PUBLISHED, SUSPENDED, REVOKED, ANNULLED};
    // rows: from, columns: to, in the order Published, Suspended, Revoked, Annulled
    var expected =
        new boolean[][] {
          {false, true, true, true},
          {true, false, true, true},
          {false, false, false, false},
          {false, false, false, false}
        };
    var out = Stream.<Arguments>builder();
    for (var i = 0; i < 4; i++) {
      for (var j = 0; j < 4; j++) {
        out.add(Arguments.of(all[i], all[j], expected[i][j]));
      }
    }
    return out.build();
  }

  @ParameterizedTest
  @MethodSource("transitions")
  void transitionTable(ProcurementStatus from, ProcurementStatus to, boolean allowed) {
    assertThat(from.allowsTransitionTo(to)).isEqualTo(allowed);
  }
}

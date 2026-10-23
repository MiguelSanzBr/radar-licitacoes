package br.dev.miguelsanzbr.radar.ops.adapter.out.togglz;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.miguelsanzbr.radar.ops.Flag;
import br.dev.miguelsanzbr.radar.ops.domain.FlagState;
import org.junit.jupiter.api.Test;
import org.togglz.core.Feature;
import org.togglz.core.annotation.EnabledByDefault;
import org.togglz.core.manager.FeatureManager;
import org.togglz.core.manager.FeatureManagerBuilder;
import org.togglz.core.repository.FeatureState;
import org.togglz.core.repository.mem.InMemoryStateRepository;
import org.togglz.core.user.NoOpUserProvider;

class TogglzFlagsTest {

  /** The features of the application are declared by name; this enum stands in for that list. */
  enum Declared implements Feature {
    INCREMENTAL_SYNC,
    CLOSE_EXPIRED,
    BACKFILL_SCHEDULED,
    ALERT_DIGEST,
    @EnabledByDefault
    MAX_SAVED_SEARCHES_LIMIT
  }

  private final FeatureManager manager =
      FeatureManagerBuilder.begin()
          .featureEnum(Declared.class)
          .stateRepository(new InMemoryStateRepository())
          .userProvider(new NoOpUserProvider())
          .build();
  private final TogglzFlags flags = new TogglzFlags(manager);

  @Test
  void aFlagFollowsItsDefaultUntilSomeoneChangesIt() {
    assertThat(flags.isActive(Flag.INCREMENTAL_SYNC)).isFalse();
    assertThat(flags.isActive(Flag.MAX_SAVED_SEARCHES_LIMIT)).isTrue();
  }

  @Test
  void flippingAFlagInTheManagerIsSeenAtOnce() {
    manager.setFeatureState(new FeatureState(Declared.INCREMENTAL_SYNC, true));
    manager.setFeatureState(new FeatureState(Declared.MAX_SAVED_SEARCHES_LIMIT, false));

    assertThat(flags.isActive(Flag.INCREMENTAL_SYNC)).isTrue();
    assertThat(flags.isActive(Flag.MAX_SAVED_SEARCHES_LIMIT)).isFalse();
  }

  @Test
  void theStateOfEveryFlagIsListedByName() {
    manager.setFeatureState(new FeatureState(Declared.ALERT_DIGEST, true));

    assertThat(flags.states())
        .containsExactly(
            new FlagState("ALERT_DIGEST", true),
            new FlagState("BACKFILL_SCHEDULED", false),
            new FlagState("CLOSE_EXPIRED", false),
            new FlagState("INCREMENTAL_SYNC", false),
            new FlagState("MAX_SAVED_SEARCHES_LIMIT", true));
  }
}

package br.dev.miguelsanzbr.radar.searchapi;

import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Active only under startup-probe: records the profiles of the started context, then closes it. */
@Component
@Profile("startup-probe")
class StartupProbe {

  static volatile List<String> profiles = List.of();

  @EventListener
  void onReady(ApplicationReadyEvent event) {
    profiles = List.of(event.getApplicationContext().getEnvironment().getActiveProfiles());
    event.getApplicationContext().close();
  }
}

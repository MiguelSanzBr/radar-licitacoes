package br.dev.miguelsanzbr.radar;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Active only under the noop profile: records that the context started, then closes it. */
@Component
@Profile("noop")
class StartupProbe {

  static final AtomicBoolean STARTED = new AtomicBoolean();

  @EventListener
  void onReady(ApplicationReadyEvent event) {
    STARTED.set(true);
    event.getApplicationContext().close();
  }
}

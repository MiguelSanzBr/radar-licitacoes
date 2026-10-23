package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import br.dev.miguelsanzbr.radar.search.application.LiveProjection;
import java.util.Optional;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/** Controls the container of {@link ProcurementEventsListener}, when there is one. */
class KafkaLiveProjection implements LiveProjection {

  private final KafkaListenerEndpointRegistry registry;

  KafkaLiveProjection(KafkaListenerEndpointRegistry registry) {
    this.registry = registry;
  }

  private Optional<MessageListenerContainer> container() {
    return Optional.ofNullable(registry.getListenerContainer(ProcurementEventsListener.ID));
  }

  @Override
  public boolean isRunning() {
    return container().map(MessageListenerContainer::isRunning).orElse(false);
  }

  @Override
  public void stop() {
    container().ifPresent(MessageListenerContainer::stop);
  }

  @Override
  public void start() {
    container().ifPresent(MessageListenerContainer::start);
  }
}

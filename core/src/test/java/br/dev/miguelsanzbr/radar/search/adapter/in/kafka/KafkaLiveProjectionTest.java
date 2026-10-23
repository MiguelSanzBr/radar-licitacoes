package br.dev.miguelsanzbr.radar.search.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

class KafkaLiveProjectionTest {

  private final KafkaListenerEndpointRegistry registry = mock();
  private final MessageListenerContainer container = mock();

  @Test
  void stopsAndStartsTheContainerOfTheProjectorListener() {
    when(registry.getListenerContainer(ProcurementEventsListener.ID)).thenReturn(container);
    var live = new KafkaLiveProjection(registry);

    live.stop();
    live.start();

    var order = inOrder(container);
    order.verify(container).stop();
    order.verify(container).start();
  }

  @Test
  void isRunningFollowsTheContainer() {
    when(registry.getListenerContainer(ProcurementEventsListener.ID)).thenReturn(container);
    when(container.isRunning()).thenReturn(true, false);
    var live = new KafkaLiveProjection(registry);

    assertThat(live.isRunning()).isTrue();
    assertThat(live.isRunning()).isFalse();
  }

  @Test
  void withoutAProjectorListenerThereIsNothingToStop() {
    when(registry.getListenerContainer(ProcurementEventsListener.ID)).thenReturn(null);
    var live = new KafkaLiveProjection(registry);

    live.stop();
    live.start();

    assertThat(live.isRunning()).isFalse();
    verifyNoInteractions(container);
  }
}

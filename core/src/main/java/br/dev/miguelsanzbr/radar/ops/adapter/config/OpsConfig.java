package br.dev.miguelsanzbr.radar.ops.adapter.config;

import br.dev.miguelsanzbr.radar.ops.DeadLetters;
import br.dev.miguelsanzbr.radar.ops.application.HealthPanelUseCase;
import br.dev.miguelsanzbr.radar.ops.application.ReprocessDlqUseCase;
import br.dev.miguelsanzbr.radar.ops.application.port.ConsumerLags;
import br.dev.miguelsanzbr.radar.ops.application.port.DlqMessages;
import br.dev.miguelsanzbr.radar.ops.application.port.FlagStates;
import br.dev.miguelsanzbr.radar.ops.application.port.OutboxStats;
import br.dev.miguelsanzbr.radar.ops.application.port.PipelineStats;
import br.dev.miguelsanzbr.radar.ops.application.port.Republisher;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the plain use cases of the ops context; the application layer has no Spring types. */
@Configuration(proxyBeanMethods = false)
class OpsConfig {

  @Bean
  ReprocessDlqUseCase reprocessDlqUseCase(
      DlqMessages messages,
      Republisher republisher,
      Clock clock,
      @Value("${radar.outbox.topics.Procurement}") String procurementTopic,
      @Value("${radar.outbox.topics.Alert}") String alertTopic,
      @Value("${radar.ops.reprocess-page-size:500}") int pageSize) {
    return new ReprocessDlqUseCase(
        messages,
        republisher,
        clock,
        Set.of(DeadLetters.dlqTopic(procurementTopic), DeadLetters.dlqTopic(alertTopic)),
        pageSize);
  }

  @Bean
  HealthPanelUseCase healthPanelUseCase(
      OutboxStats outbox,
      PipelineStats pipeline,
      ConsumerLags lags,
      DlqMessages messages,
      FlagStates flags,
      @Value("${radar.outbox.topics.Procurement}") String procurementTopic,
      @Value("${radar.outbox.topics.Alert}") String alertTopic) {
    return new HealthPanelUseCase(
        outbox,
        pipeline,
        lags,
        messages,
        flags,
        List.of(DeadLetters.dlqTopic(procurementTopic), DeadLetters.dlqTopic(alertTopic)));
  }
}

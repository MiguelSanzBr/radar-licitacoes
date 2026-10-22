package br.dev.miguelsanzbr.radar.alerts.adapter;

import br.dev.miguelsanzbr.radar.PostgresTestConfig;
import br.dev.miguelsanzbr.radar.alerts.application.port.VerificationMailer;
import br.dev.miguelsanzbr.radar.outbox.OutboxTestConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The JVM-wide Postgres (see {@link PostgresTestConfig}) and one Spring context for the alerts ITs.
 */
@SpringBootTest(properties = "radar.outbox.relay-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfig.class, OutboxTestConfig.class, PostgresIT.RecordingMailerConfig.class})
public abstract class PostgresIT {

  /** Keeps the raw tokens that would be mailed, so tests can follow the verification link. */
  public static final class RecordingMailer implements VerificationMailer {

    public final List<String[]> sent = new ArrayList<>();

    @Override
    public synchronized void send(String email, String rawToken) {
      sent.add(new String[] {email, rawToken});
    }

    public synchronized String lastToken() {
      return sent.getLast()[1];
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class RecordingMailerConfig {
    @Bean
    @Primary
    RecordingMailer recordingMailer() {
      return new RecordingMailer();
    }
  }

  @Autowired protected JdbcClient jdbc;
  @Autowired protected RecordingMailer mailer;

  @BeforeEach
  void cleanDatabase() {
    jdbc.sql(
            "TRUNCATE alerts.alert_delivery, alerts.saved_search, alerts.verification_token,"
                + " alerts.idempotency_key, alerts.subscriber, alerts.processed_event")
        .update();
    mailer.sent.clear();
  }
}

package br.dev.miguelsanzbr.radar.alerts.application;

import br.dev.miguelsanzbr.radar.alerts.application.port.AlertDeliveries;
import br.dev.miguelsanzbr.radar.alerts.application.port.AlertEvents;
import br.dev.miguelsanzbr.radar.alerts.application.port.MatcherMetrics;
import br.dev.miguelsanzbr.radar.alerts.application.port.ProcessedEvents;
import br.dev.miguelsanzbr.radar.alerts.application.port.ReverseSearch;
import br.dev.miguelsanzbr.radar.alerts.application.port.UnitOfWork;
import br.dev.miguelsanzbr.radar.alerts.domain.AlertMatched;
import br.dev.miguelsanzbr.radar.alerts.domain.ProcurementFacts;
import java.time.Clock;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Tells subscribers about a procurement that matches one of their saved searches. One transaction
 * per event: inbox, reverse search, one delivery row per match and one {@link AlertMatched} per new
 * delivery. A pair already delivered stays silent, so updates and replays never alert twice.
 */
public class MatchProcurementUseCase {

  public static final String CONSUMER = "alerts-matcher";

  /**
   * Snapshot fields that a saved search can see: text, value, window, location, organ, modality.
   */
  private static final Set<String> SEARCHED_FIELDS =
      Set.of("objectDescription", "estimatedValue", "proposalWindow", "unit", "organ", "modality");

  private final ReverseSearch search;
  private final AlertDeliveries deliveries;
  private final ProcessedEvents inbox;
  private final AlertEvents events;
  private final MatcherMetrics metrics;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public MatchProcurementUseCase(
      ReverseSearch search,
      AlertDeliveries deliveries,
      ProcessedEvents inbox,
      AlertEvents events,
      MatcherMetrics metrics,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.search = search;
    this.deliveries = deliveries;
    this.inbox = inbox;
    this.events = events;
    this.metrics = metrics;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /** Returns how many new alerts the procurement produced. */
  public int onDiscovered(UUID eventId, ProcurementFacts procurement) {
    var alerts = metrics.timed(() -> unitOfWork.inTransaction(() -> match(eventId, procurement)));
    metrics.matched(alerts);
    return alerts;
  }

  /** Matches only when the update touched something a saved search looks at. */
  public int onUpdated(UUID eventId, ProcurementFacts procurement, Collection<String> changed) {
    if (changed.stream().noneMatch(SEARCHED_FIELDS::contains)) {
      return 0;
    }
    return onDiscovered(eventId, procurement);
  }

  private int match(UUID eventId, ProcurementFacts procurement) {
    var now = clock.instant();
    if (!inbox.firstTime(CONSUMER, eventId, now)) {
      return 0;
    }
    var alerts = 0;
    for (var match : search.find(procurement, now)) {
      if (deliveries.insertIfAbsent(match.savedSearchId(), procurement.id(), now)) {
        events.matched(AlertMatched.of(match, procurement), now);
        alerts++;
      }
    }
    return alerts;
  }
}

package br.dev.miguelsanzbr.radar.ingest.application;

import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionCursorRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.IngestionRunRepository;
import br.dev.miguelsanzbr.radar.ingest.application.port.PncpGateway;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionCursor;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionKind;
import br.dev.miguelsanzbr.radar.ingest.domain.IngestionRun;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * Reads what the PNCP updated since the modality's watermark, one window per call, one transaction
 * per page. The watermark only advances after every page committed, so a failed sync is simply read
 * again by the next one.
 *
 * <p>The PNCP updates listing takes whole days, so the window is widened to the São Paulo days it
 * touches and every item of those days is applied: processing is idempotent (an unchanged notice
 * emits nothing), so the excess only costs reads.
 */
public final class IncrementalSyncUseCase {

  private static final ZoneId PNCP_ZONE = ZoneId.of("America/Sao_Paulo");

  private final PncpGateway gateway;
  private final IngestionCursorRepository cursors;
  private final IngestionRunRepository runs;
  private final PageProcessor pages;
  private final Clock clock;
  private final Duration initialLookback;
  private final Duration overlap;

  public IncrementalSyncUseCase(
      PncpGateway gateway,
      IngestionCursorRepository cursors,
      IngestionRunRepository runs,
      PageProcessor pages,
      Clock clock,
      Duration initialLookback,
      Duration overlap) {
    this.gateway = gateway;
    this.cursors = cursors;
    this.runs = runs;
    this.pages = pages;
    this.clock = clock;
    this.initialLookback = initialLookback;
    this.overlap = overlap;
  }

  /**
   * Syncs one modality and returns its finished run. A failure marks the run FAILED, leaves the
   * cursor where it was and is reported as {@link IncrementalSyncFailedException}.
   *
   * <p>shortcut: ticks of one instance never overlap (fixed delay), but nothing stops two instances
   * from syncing the same modality together; the idempotent processing makes that harmless, an
   * advisory lock would make it cheap.
   */
  public IngestionRun sync(int modalityId) {
    var now = clock.instant();
    var cursor =
        cursors
            .find(modalityId)
            .orElseGet(() -> IngestionCursor.initial(modalityId, now, initialLookback, overlap));
    var window = cursor.nextWindow(now);
    var from = window.start().atZone(PNCP_ZONE).toLocalDate();
    var to = window.end().atZone(PNCP_ZONE).toLocalDate();
    var run =
        runs.save(
            IngestionRun.start(
                IngestionKind.INCREMENTAL, modalityId, window.start(), window.end(), clock));
    var newest = cursor.watermark();
    var page = run.nextPage();
    try {
      while (true) {
        var result = gateway.updates(from, to, modalityId, page);
        if (result.totalPages() == 0) {
          run = pages.finishEmpty(run);
          break;
        }
        run = pages.process(run, page, result);
        for (var item : result.items()) {
          if (item.pncpUpdatedAt().isAfter(newest)) {
            newest = item.pncpUpdatedAt();
          }
        }
        if (result.isLast()) {
          break;
        }
        page++;
      }
    } catch (RuntimeException e) {
      pages.fail(run, e);
      throw new IncrementalSyncFailedException(modalityId, page, e);
    }
    // never past now (a skewed PNCP timestamp must not skip what is still to be read) and never
    // before the stored watermark (a clock that went backwards)
    var capped = newest.isAfter(now) ? now : newest;
    var target = capped.isBefore(cursor.watermark()) ? cursor.watermark() : capped;
    cursors.save(cursor.advance(target, clock));
    return run;
  }
}

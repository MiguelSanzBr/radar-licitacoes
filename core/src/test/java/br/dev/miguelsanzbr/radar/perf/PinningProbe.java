package br.dev.miguelsanzbr.radar.perf;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/**
 * Finds virtual threads pinned for long, from JFR's {@code jdk.VirtualThreadPinned}. Under {@code
 * -Pjfr} the JVM already runs a recording named {@value #RECORDING} for the whole test run, and its
 * snapshot is read; without it the probe records just the workload it is given.
 */
public final class PinningProbe {

  public static final String RECORDING = "pinning";
  public static final Duration THRESHOLD = Duration.ofMillis(20);

  private static final String EVENT = "jdk.VirtualThreadPinned";

  private PinningProbe() {}

  /** Whether the JVM was started with the {@value #RECORDING} recording ({@code -Pjfr}). */
  public static boolean runsUnderJfrProfile() {
    return FlightRecorder.getFlightRecorder().getRecordings().stream()
        .anyMatch(r -> RECORDING.equals(r.getName()));
  }

  /** Runs the workload and returns every pin of {@link #THRESHOLD} or more seen meanwhile. */
  public static List<RecordedEvent> pinsDuring(Runnable workload) throws IOException {
    var file = Files.createTempFile("pinning", ".jfr");
    try {
      if (runsUnderJfrProfile()) {
        workload.run();
        try (var snapshot = FlightRecorder.getFlightRecorder().takeSnapshot()) {
          snapshot.dump(file);
        }
      } else {
        try (var recording = new Recording()) {
          recording.enable(EVENT).withThreshold(THRESHOLD).withStackTrace();
          recording.start();
          workload.run();
          recording.stop();
          recording.dump(file);
        }
      }
      return RecordingFile.readAllEvents(file).stream()
          .filter(e -> EVENT.equals(e.getEventType().getName()))
          .filter(e -> e.getDuration().compareTo(THRESHOLD) >= 0)
          .toList();
    } finally {
      Files.deleteIfExists(file);
    }
  }

  /**
   * Whether the pinned thread was running code of the given package prefix when it pinned. A trace
   * cut at the recording's stack depth (or missing) may hide the frames, so it counts as a match.
   */
  public static boolean passesThrough(RecordedEvent pin, String packagePrefix) {
    var trace = pin.getStackTrace();
    return trace == null
        || trace.isTruncated()
        || trace.getFrames().stream()
            .anyMatch(f -> f.getMethod().getType().getName().startsWith(packagePrefix));
  }
}

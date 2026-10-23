package br.dev.miguelsanzbr.radar.perf;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.Simulation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S3: pages 1, 100 and 1000 of the unfiltered list, by keyset and by {@code OFFSET}. The keyset
 * cursor of a deep page is asked once, before the load, from the bench-only OFFSET endpoint ({@code
 * radar.bench.offset-endpoint=true}), which returns the cursor after its last row.
 */
public class S3Pagination extends Simulation {

  private static final int LIMIT = 20;
  private static final int[] DEPTHS = {1, 100, 1000};
  private static final Pattern NEXT_CURSOR = Pattern.compile("\"nextCursor\":\"([^\"]+)\"");

  private final Map<Integer, String> cursors = new LinkedHashMap<>();

  @Override
  public void before() {
    var client = HttpClient.newHttpClient();
    for (int depth : DEPTHS) {
      cursors.put(depth, depth == 1 ? "" : cursorBefore(client, depth));
    }
  }

  private static String cursorBefore(HttpClient client, int depth) {
    var uri = URI.create(Bench.baseUrl() + "/bench/offset?limit=" + LIMIT + "&page=" + (depth - 1));
    try {
      var response =
          client.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "S3 precisa de radar.bench.offset-endpoint=true: "
                + uri
                + " respondeu "
                + response.statusCode());
      }
      var found = NEXT_CURSOR.matcher(response.body());
      if (!found.find()) {
        throw new IllegalStateException("S3 sem nextCursor em " + uri);
      }
      return found.group(1);
    } catch (IOException e) {
      throw new IllegalStateException("S3 nao alcancou " + uri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("S3 interrompido", e);
    }
  }

  private ChainBuilder depth(int depth, String phase) {
    return exec(http("S3 keyset p" + depth + phase)
            .get(session -> "/api/v1/procurements?limit=" + LIMIT + cursor(depth))
            .check(status().is(200)))
        .pause(Bench.think())
        .exec(
            http("S3 offset p" + depth + phase)
                .get("/bench/offset?limit=" + LIMIT + "&page=" + depth)
                .check(status().is(200)))
        .pause(Bench.think());
  }

  private String cursor(int depth) {
    var cursor = cursors.get(depth);
    return cursor.isEmpty() ? "" : "&cursor=" + cursor;
  }

  public S3Pagination() {
    setUp(
            Bench.run(
                "s3",
                Bench.users("s3", 10),
                phase ->
                    scenario("S3" + phase)
                        .exec(depth(1, phase))
                        .exec(depth(100, phase))
                        .exec(depth(1000, phase))))
        .protocols(Bench.protocol());
  }
}

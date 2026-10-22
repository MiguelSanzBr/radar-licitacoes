package br.dev.miguelsanzbr.radar.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

/** Pure calculations over recorded data: percentiles, field presence, personal-id heuristics. */
enum Stats {
  ; // no constants: a holder of static methods that cannot be instantiated

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  /** Nearest-rank percentile; p in [0, 100], values need not be sorted. */
  static long percentile(List<Long> values, double p) {
    if (values.isEmpty()) {
      throw new IllegalArgumentException("no values");
    }
    List<Long> sorted = new ArrayList<>(values);
    sorted.sort(null);
    int rank = Math.max(1, (int) Math.ceil(p / 100.0 * sorted.size()));
    return sorted.get(rank - 1);
  }

  /** The list of a response: the root array, the "data" array, or a missing node. */
  static JsonNode items(JsonNode body) {
    JsonNode data = body.isArray() ? body : body.path("data");
    return data.isArray() ? data : MissingNode.getInstance();
  }

  /** Per field path (top level and one nested level): how many items have it non-null. */
  static ObjectNode fieldStats(JsonNode items) {
    Map<String, Integer> present = new TreeMap<>();
    for (JsonNode item : items) {
      for (Map.Entry<String, JsonNode> field : item.properties()) {
        count(present, field.getKey(), field.getValue());
        for (Map.Entry<String, JsonNode> nested : field.getValue().properties()) {
          count(present, field.getKey() + "." + nested.getKey(), nested.getValue());
        }
      }
    }
    ObjectNode out = JSON.createObjectNode();
    present.forEach(
        (path, n) -> out.putObject(path).put("present", n).put("null", items.size() - n));
    return out;
  }

  private static void count(Map<String, Integer> present, String path, JsonNode value) {
    present.merge(path, value.isNull() ? 0 : 1, Integer::sum);
  }

  /** Fields that look like a CPF, in the response itself and in each item of its list. */
  static int cpfLike(JsonNode body) {
    int total = cpfLikeFields(body);
    for (JsonNode item : items(body)) {
      total += cpfLikeFields(item);
    }
    return total;
  }

  private static int cpfLikeFields(JsonNode object) {
    int total = 0;
    for (Map.Entry<String, JsonNode> field : object.properties()) {
      String name = field.getKey().toLowerCase(java.util.Locale.ROOT);
      JsonNode value = field.getValue();
      boolean cpfNamed = name.contains("cpf") && !value.isNull();
      boolean elevenDigits =
          name.startsWith("ni") && value.isString() && value.stringValue().matches("\\d{11}");
      total += cpfNamed || elevenDigits ? 1 : 0;
    }
    return total;
  }
}

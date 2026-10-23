package br.dev.miguelsanzbr.radar.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** ADR-0027: a full Redis may only evict buckets (all have a TTL), never refuse writes. */
class RedisComposeTest {

  @Test
  void redisEvictsTheKeysClosestToExpiryWhenFull() throws IOException {
    var compose = Path.of("..", "infra", "compose", "docker-compose.yml");
    Map<?, ?> services;
    try (var in = Files.newInputStream(compose)) {
      services = (Map<?, ?>) ((Map<?, ?>) new Yaml().load(in)).get("services");
    }

    var command =
        ((List<?>) ((Map<?, ?>) services.get("redis")).get("command"))
            .stream().map(String::valueOf).toList();

    assertThat(command).containsSubsequence("--maxmemory", "64mb");
    assertThat(command.indexOf("--maxmemory-policy") + 1)
        .isPositive()
        .satisfies(i -> assertThat(command.get(i)).isEqualTo("volatile-ttl"));
  }
}

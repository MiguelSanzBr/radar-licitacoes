package br.dev.miguelsanzbr.radar.ops.adapter.out.kafka;

import br.dev.miguelsanzbr.radar.ops.application.port.ConsumerLags;
import br.dev.miguelsanzbr.radar.ops.domain.ConsumerLag;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * Lag per group and topic: the end offset of each partition the group has committed minus its
 * committed offset. One short lived admin client per reading, two broker calls (the commits of all
 * groups, then the end offsets) under one deadline, so a broker that does not answer ends the
 * reading with an error, which the panel shows as unknown, instead of hanging it.
 */
@Component
class KafkaConsumerLags implements ConsumerLags {

  private final Supplier<Admin> admins;
  private final List<String> groups;
  private final Duration timeout;

  @Autowired
  KafkaConsumerLags(
      KafkaAdmin admin,
      @Value("${radar.ops.consumer-groups}") List<String> groups,
      @Value("${radar.ops.kafka-timeout:5s}") Duration timeout) {
    this(admin.getConfigurationProperties(), groups, timeout);
  }

  KafkaConsumerLags(Map<String, Object> adminProperties, List<String> groups, Duration timeout) {
    this(() -> Admin.create(properties(adminProperties, timeout)), groups, timeout);
  }

  KafkaConsumerLags(Supplier<Admin> admins, List<String> groups, Duration timeout) {
    this.admins = admins;
    this.groups = List.copyOf(groups);
    this.timeout = timeout;
  }

  private static Map<String, Object> properties(Map<String, Object> base, Duration timeout) {
    var properties = new HashMap<>(base);
    // why: the admin client rejects an API timeout below its request timeout
    properties.put("request.timeout.ms", (int) timeout.toMillis());
    properties.put("default.api.timeout.ms", (int) timeout.toMillis());
    return properties;
  }

  @Override
  public List<ConsumerLag> read() {
    var deadline = System.nanoTime() + timeout.toNanos();
    var admin = admins.get();
    try {
      var specs = new HashMap<String, ListConsumerGroupOffsetsSpec>();
      groups.forEach(group -> specs.put(group, new ListConsumerGroupOffsetsSpec()));
      var commits =
          admin
              .listConsumerGroupOffsets(specs)
              .all()
              .get(remaining(deadline), TimeUnit.MILLISECONDS);
      var partitions = new HashSet<TopicPartition>();
      commits
          .values()
          .forEach(
              offsets ->
                  offsets.forEach(
                      (partition, offset) -> {
                        if (offset != null) {
                          partitions.add(partition);
                        }
                      }));
      if (partitions.isEmpty()) {
        return List.of();
      }
      var ends =
          admin
              .listOffsets(
                  partitions.stream()
                      .collect(
                          Collectors.toMap(
                              partition -> partition, partition -> OffsetSpec.latest())))
              .all()
              .get(remaining(deadline), TimeUnit.MILLISECONDS);
      var lags = new ArrayList<ConsumerLag>();
      for (var group : groups) {
        var perTopic = new TreeMap<String, Long>();
        commits
            .getOrDefault(group, Map.of())
            .forEach(
                (partition, offset) -> {
                  if (offset != null) {
                    perTopic.merge(
                        partition.topic(),
                        Math.max(0, ends.get(partition).offset() - offset.offset()),
                        Long::sum);
                  }
                });
        perTopic.forEach((topic, lag) -> lags.add(new ConsumerLag(group, topic, lag)));
      }
      return lags;
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("could not read the consumer groups", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while reading the consumer groups", e);
    } finally {
      admin.close(Duration.ofSeconds(1));
    }
  }

  private static long remaining(long deadline) {
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
  }
}

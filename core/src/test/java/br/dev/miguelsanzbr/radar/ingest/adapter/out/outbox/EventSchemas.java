package br.dev.miguelsanzbr.radar.ingest.adapter.out.outbox;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

/**
 * Loads the event schemas shipped by the contracts module, resolving their ids on the classpath.
 */
public final class EventSchemas {

  private static final String ID_PREFIX = "https://radar.miguelsanzbr.dev/schemas/";

  private static final SchemaRegistry REGISTRY =
      SchemaRegistry.withDefaultDialect(
          SpecificationVersion.DRAFT_2020_12,
          builder ->
              builder
                  .schemaIdResolvers(ids -> ids.mapPrefix(ID_PREFIX, "classpath:"))
                  .schemaRegistryConfig(
                      SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

  private EventSchemas() {}

  /** For example {@code "procurement-discovered.v1"} or {@code "envelope"}. */
  public static Schema load(String name) {
    return REGISTRY.getSchema(SchemaLocation.of(ID_PREFIX + "events/" + name + ".schema.json"));
  }
}

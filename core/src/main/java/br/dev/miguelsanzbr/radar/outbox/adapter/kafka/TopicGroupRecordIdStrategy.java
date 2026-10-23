package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import io.apicurio.registry.resolver.ParsedSchema;
import io.apicurio.registry.resolver.data.Record;
import io.apicurio.registry.resolver.strategy.ArtifactReference;
import io.apicurio.registry.serde.avro.strategy.RecordIdStrategy;
import io.apicurio.registry.serde.data.SerdeRecord;
import org.apache.avro.Schema;

/**
 * Registers each event type as its own artifact (the record name) in the group named after the
 * topic, so a compatibility rule set on the group covers the events of that topic and no more.
 */
public class TopicGroupRecordIdStrategy extends RecordIdStrategy {

  @Override
  public ArtifactReference artifactReference(Record<Object> data, ParsedSchema<Schema> schema) {
    var record = super.artifactReference(data, schema);
    return ArtifactReference.builder()
        .groupId(((SerdeRecord<?>) data).metadata().getTopic())
        .artifactId(record.getArtifactId())
        .version(record.getVersion())
        .build();
  }
}

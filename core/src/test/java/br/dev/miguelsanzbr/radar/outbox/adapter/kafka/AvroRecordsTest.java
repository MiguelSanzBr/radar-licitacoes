package br.dev.miguelsanzbr.radar.outbox.adapter.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.avro.Schema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AvroRecordsTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Schema schema(String fieldType) {
    return new Schema.Parser()
        .parse(
            """
            {"type":"record","name":"R","fields":[{"name":"f","type":%s}]}
            """
                .formatted(fieldType));
  }

  @Test
  void aTypeTheContractsDoNotUseIsRejectedByName() {
    var map = schema("{\"type\":\"map\",\"values\":\"string\"}");

    assertThatThrownBy(() -> AvroRecords.toRecord(map, JSON.readTree("{\"f\":{}}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unsupported avro type MAP");
  }

  @Test
  void anObjectWithTheFieldsOfTheSchemaBecomesTheRecord() {
    var record = AvroRecords.toRecord(schema("[\"null\",\"long\"]"), JSON.readTree("{\"f\":7}"));

    assertThat(record.get("f")).isEqualTo(7L);
  }
}

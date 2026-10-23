CREATE SCHEMA ops;

-- A message a consumer group gave up on, copied from a dead-letter topic. The unique key is the
-- original record and the group that failed it, not the position in the dead-letter topic: a dead
-- letter published twice (lost ack) or delivered twice to the recorder adds no second row, while two
-- groups failing the same record keep one row each.
-- topic, kafka_partition and kafka_offset locate the record in the dead-letter topic.
CREATE TABLE ops.dlq_message (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  topic            text        NOT NULL,
  kafka_partition  integer     NOT NULL,
  kafka_offset     bigint      NOT NULL,
  message_key      text,
  message_value    text,
  headers          jsonb       NOT NULL,
  error_class      text,
  error_message    text,
  original_topic     text    NOT NULL,
  original_partition integer NOT NULL,
  original_offset    bigint  NOT NULL,
  consumer_group     text    NOT NULL,
  recorded_at      timestamptz NOT NULL,
  reprocessed_at   timestamptz,
  reprocess_result text,
  UNIQUE (original_topic, original_partition, original_offset, consumer_group)
);

CREATE INDEX dlq_message_pending_idx ON ops.dlq_message (topic, id) WHERE reprocessed_at IS NULL;

-- State of the feature flags (Togglz JDBC repository). A flag without a row keeps its default.
CREATE TABLE ops.togglz_feature (
  feature_name    text PRIMARY KEY,
  feature_enabled integer NOT NULL,
  strategy_id     text,
  strategy_params text
);

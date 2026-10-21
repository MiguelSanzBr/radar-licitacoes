CREATE TABLE outbox.outbox_event (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id        uuid        NOT NULL UNIQUE,
  aggregate_type  text        NOT NULL,
  aggregate_id    text        NOT NULL,
  event_type      text        NOT NULL,
  schema_version  smallint    NOT NULL,
  payload         jsonb       NOT NULL,
  headers         jsonb       NOT NULL,
  created_at      timestamptz NOT NULL DEFAULT now(),
  published_at    timestamptz,
  attempts        smallint    NOT NULL DEFAULT 0,
  last_error      text
);

CREATE INDEX outbox_pending_idx ON outbox.outbox_event (id) WHERE published_at IS NULL;

ALTER TABLE outbox.outbox_event
  SET (autovacuum_vacuum_scale_factor = 0.01, autovacuum_analyze_scale_factor = 0.005);

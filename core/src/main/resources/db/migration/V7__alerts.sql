CREATE TABLE alerts.subscriber (
  id uuid PRIMARY KEY, email citext UNIQUE, consent_at timestamptz NOT NULL,
  verified_at timestamptz, deleted_at timestamptz, created_at timestamptz NOT NULL
);

CREATE TABLE alerts.saved_search (
  id uuid PRIMARY KEY, subscriber_id uuid NOT NULL REFERENCES alerts.subscriber,
  name text NOT NULL, query_text text NOT NULL, filters jsonb NOT NULL DEFAULT '{}',
  frequency text NOT NULL, active boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL
);
CREATE INDEX ON alerts.saved_search (active) WHERE active;
CREATE INDEX ON alerts.saved_search (subscriber_id);

CREATE TABLE alerts.alert_delivery (
  saved_search_id uuid NOT NULL REFERENCES alerts.saved_search,
  procurement_id text NOT NULL, matched_at timestamptz NOT NULL,
  sent_at timestamptz, channel text NOT NULL DEFAULT 'email',
  PRIMARY KEY (saved_search_id, procurement_id)
);
CREATE INDEX ON alerts.alert_delivery (sent_at) WHERE sent_at IS NULL;

-- Inbox of the matcher (PR 3.2), same shape as in the search schema.
CREATE TABLE alerts.processed_event (
  consumer text NOT NULL, event_id uuid NOT NULL, processed_at timestamptz NOT NULL,
  PRIMARY KEY (consumer, event_id)
);

CREATE TABLE alerts.idempotency_key (
  key text NOT NULL, subscriber_id uuid NOT NULL,
  request_hash bytea NOT NULL, response_status int, response_body jsonb,
  created_at timestamptz NOT NULL,
  PRIMARY KEY (subscriber_id, key)
);
CREATE INDEX ON alerts.idempotency_key (created_at);

CREATE TABLE alerts.verification_token (
  token_hash bytea PRIMARY KEY, subscriber_id uuid NOT NULL REFERENCES alerts.subscriber,
  expires_at timestamptz NOT NULL, used_at timestamptz
);
CREATE INDEX ON alerts.verification_token (subscriber_id);

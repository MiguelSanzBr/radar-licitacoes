-- Only the SHA-256 of the key and its 8-character prefix are stored; owner_subject is the opaque
-- subject of the identity provider, not a reference to alerts.subscriber (identity depends on no
-- other context), so erasing a subscriber leaves the keys revocable by their owner.
CREATE TABLE identity.api_key (
  id            uuid        PRIMARY KEY,
  owner_subject text        NOT NULL,
  key_hash      bytea       NOT NULL UNIQUE,
  prefix        char(8)     NOT NULL,
  tier          text        NOT NULL CHECK (tier IN ('free', 'pro')),
  created_at    timestamptz NOT NULL,
  revoked_at    timestamptz,
  -- the prefix names the key to its owner, so it must be unique among all of the owner's keys
  UNIQUE (owner_subject, prefix)
);
CREATE INDEX api_key_owner_active_idx ON identity.api_key (owner_subject) WHERE revoked_at IS NULL;

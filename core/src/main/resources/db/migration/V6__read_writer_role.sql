-- Roles are cluster-wide, so the role may already exist when another database of the cluster
-- was migrated first.
DO $do$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'radar_read_writer') THEN
    EXECUTE format('CREATE ROLE radar_read_writer LOGIN PASSWORD %L', '${readWriterPassword}');
  END IF;
END
$do$;

-- TRUNCATE is for the replay, which rebuilds the whole schema.
GRANT USAGE ON SCHEMA search TO radar_read_writer;
GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA search TO radar_read_writer;
ALTER DEFAULT PRIVILEGES IN SCHEMA search
  GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON TABLES TO radar_read_writer;

-- Nothing is granted on ingest, outbox, alerts or identity: the read side never sees them.

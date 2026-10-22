-- Optimistic lock of the run: a stale copy cannot overwrite a newer cursor.
ALTER TABLE ingest.ingestion_run ADD COLUMN version integer NOT NULL DEFAULT 0;

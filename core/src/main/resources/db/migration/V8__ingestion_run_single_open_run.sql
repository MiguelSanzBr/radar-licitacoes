-- A window has at most one unfinished run, so a restart or a second request resumes it
-- instead of starting a parallel one. Finished runs of the same window stay as history.
CREATE UNIQUE INDEX ingestion_run_open_window
  ON ingest.ingestion_run (kind, modality_id, window_start, window_end)
  WHERE status IN ('RUNNING', 'FAILED');

-- What the daily digest shows, kept on the delivery row of a DAILY alert until the digest sends it.
ALTER TABLE alerts.alert_delivery
  ADD COLUMN object_description text,
  ADD COLUMN organ_name text,
  ADD COLUMN uf char(2),
  ADD COLUMN municipality text,
  ADD COLUMN estimated_value numeric(18,2),
  ADD COLUMN proposal_closing_at timestamptz,
  ADD COLUMN detail_path text;

-- Identity provider subject (JWT "sub") of the account that gave consent; cleared on erasure.
ALTER TABLE alerts.subscriber ADD COLUMN external_subject text
  CONSTRAINT subscriber_external_subject_key UNIQUE;

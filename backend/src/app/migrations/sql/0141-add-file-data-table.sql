CREATE TABLE file_data (
  file_id uuid NOT NULL REFERENCES file(id) DEFERRABLE,
  id uuid NOT NULL,

  created_at timestamptz NOT NULL DEFAULT now(),
  modified_at timestamptz NOT NULL DEFAULT now(),
  deleted_at timestamptz NULL,

  type text NOT NULL,
  backend text NULL,

  metadata jsonb NULL,
  data bytea NULL,

  PRIMARY KEY (file_id, id)

);

CREATE INDEX file_data__deleted_at__idx
    ON file_data (deleted_at, file_id, id)
 WHERE deleted_at IS NOT NULL;

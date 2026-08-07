DROP TABLE IF EXISTS task;
DROP TABLE IF EXISTS task_completed;
DROP TABLE IF EXISTS task_default;

CREATE TABLE task (
  id uuid DEFAULT uuid_generate_v4(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  modified_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  completed_at timestamptz NULL DEFAULT NULL,
  scheduled_at timestamptz NOT NULL,
  priority smallint DEFAULT 100,

  queue text NOT NULL,

  name text NOT NULL,
  props jsonb NOT NULL,

  error text NULL DEFAULT NULL,
  retry_num smallint NOT NULL DEFAULT 0,
  max_retries smallint NOT NULL DEFAULT 3,
  status text NOT NULL DEFAULT 'new',

  PRIMARY KEY (id, status)
);

CREATE INDEX task__scheduled_at__queue__idx
    ON task (scheduled_at, queue)
 WHERE status = 'new' or status = 'retry';

ALTER TABLE task
  ALTER COLUMN queue SET STORAGE external,
  ALTER COLUMN name SET STORAGE external,
  ALTER COLUMN props SET STORAGE external,
  ALTER COLUMN status SET STORAGE external,
  ALTER COLUMN error SET STORAGE external;


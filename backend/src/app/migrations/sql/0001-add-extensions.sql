CREATE OR REPLACE FUNCTION uuid_generate_v4()
  RETURNS uuid
  LANGUAGE plpgsql
  AS $$
  BEGIN
    RETURN md5(random()::text || clock_timestamp()::text)::uuid;
  END;
$$;

CREATE OR REPLACE FUNCTION uuid_nil()
  RETURNS uuid
  LANGUAGE sql
  IMMUTABLE
  AS $$
  SELECT '00000000-0000-0000-0000-000000000000'::uuid;
$$;

CREATE FUNCTION update_modified_at()
  RETURNS TRIGGER AS $updt$
  BEGIN
    NEW.modified_at := clock_timestamp();
    RETURN NEW;
  END;
$updt$ LANGUAGE plpgsql;

CREATE TABLE pending_to_delete (
  id uuid PRIMARY KEY DEFAULT uuid_generate_v4(),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  type text NOT NULL,
  data jsonb NOT NULL
);

CREATE FUNCTION handle_delete()
  RETURNS TRIGGER AS $pagechange$
  BEGIN
    INSERT INTO pending_to_delete (type, data)
    VALUES (TG_TABLE_NAME, row_to_json(OLD));
    RETURN OLD;
  END;
$pagechange$ LANGUAGE plpgsql;

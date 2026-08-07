CREATE OR REPLACE FUNCTION try_current_setting(name text)
  RETURNS text
  LANGUAGE plpgsql
  AS $$
  BEGIN
    RETURN current_setting(name);
  EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
  END;
$$;

CREATE OR REPLACE FUNCTION raise_deletion_protection()
  RETURNS TRIGGER AS $$
  BEGIN
    RAISE EXCEPTION 'unable to proceed to delete row on "%"', TG_TABLE_NAME
          USING HINT = 'disable deletion protection with "SET rules.deletion_protection TO off"';
    RETURN NULL;
  END;
$$ LANGUAGE plpgsql;

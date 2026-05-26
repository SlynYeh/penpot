--- Add deletion protection
DROP TRIGGER IF EXISTS deletion_protection__tgr ON team;
CREATE TRIGGER deletion_protection__tgr
BEFORE DELETE ON team FOR EACH STATEMENT
  WHEN ((try_current_setting('rules.deletion_protection') IN ('on', '')) OR
        (try_current_setting('rules.deletion_protection') IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();

ALTER TABLE team
 DROP CONSTRAINT team_photo_id_fkey;
ALTER TABLE team
  ADD CONSTRAINT team_photo_id_fkey FOREIGN KEY (photo_id) REFERENCES storage_object(id) DEFERRABLE;

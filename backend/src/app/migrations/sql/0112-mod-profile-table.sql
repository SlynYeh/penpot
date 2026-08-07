ALTER TABLE profile
 DROP CONSTRAINT profile_photo_id_fkey;
ALTER TABLE profile
  ADD CONSTRAINT profile_photo_id_fkey FOREIGN KEY (photo_id) REFERENCES storage_object(id) DEFERRABLE;
ALTER TABLE profile
 DROP CONSTRAINT profile_default_project_id_fkey;
ALTER TABLE profile
  ADD CONSTRAINT profile_default_project_id_fkey FOREIGN KEY (default_project_id) REFERENCES project(id) DEFERRABLE;
ALTER TABLE profile
 DROP CONSTRAINT profile_default_team_id_fkey;
ALTER TABLE profile
  ADD CONSTRAINT profile_default_team_id_fkey FOREIGN KEY (default_team_id) REFERENCES team(id) DEFERRABLE;

--- Add deletion protection
DROP TRIGGER IF EXISTS deletion_protection__tgr ON profile;
CREATE TRIGGER deletion_protection__tgr
BEFORE DELETE ON profile FOR EACH STATEMENT
  WHEN ((try_current_setting('rules.deletion_protection') IN ('on', '')) OR
        (try_current_setting('rules.deletion_protection') IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();


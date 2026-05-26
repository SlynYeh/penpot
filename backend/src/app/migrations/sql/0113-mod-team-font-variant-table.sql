--- Remove ON DELETE SET NULL from foreign constraint on
--- storage_object table
ALTER TABLE team_font_variant
 DROP CONSTRAINT team_font_variant_otf_file_id_fkey;
ALTER TABLE team_font_variant
  ADD CONSTRAINT team_font_variant_otf_file_id_fkey FOREIGN KEY (otf_file_id) REFERENCES storage_object(id) DEFERRABLE;
ALTER TABLE team_font_variant
 DROP CONSTRAINT team_font_variant_ttf_file_id_fkey;
ALTER TABLE team_font_variant
  ADD CONSTRAINT team_font_variant_ttf_file_id_fkey FOREIGN KEY (ttf_file_id) REFERENCES storage_object(id) DEFERRABLE;
ALTER TABLE team_font_variant
 DROP CONSTRAINT team_font_variant_woff1_file_id_fkey;
ALTER TABLE team_font_variant
  ADD CONSTRAINT team_font_variant_woff1_file_id_fkey FOREIGN KEY (woff1_file_id) REFERENCES storage_object(id) DEFERRABLE;
ALTER TABLE team_font_variant
 DROP CONSTRAINT team_font_variant_woff2_file_id_fkey;
ALTER TABLE team_font_variant
  ADD CONSTRAINT team_font_variant_woff2_file_id_fkey FOREIGN KEY (woff2_file_id) REFERENCES storage_object(id) DEFERRABLE;
ALTER TABLE team_font_variant
 DROP CONSTRAINT team_font_variant_team_id_fkey;
ALTER TABLE team_font_variant
  ADD CONSTRAINT team_font_variant_team_id_fkey FOREIGN KEY (team_id) REFERENCES team(id) DEFERRABLE;

--- Add deletion protection
DROP TRIGGER IF EXISTS deletion_protection__tgr ON team_font_variant;
CREATE TRIGGER deletion_protection__tgr
BEFORE DELETE ON team_font_variant FOR EACH STATEMENT
  WHEN ((try_current_setting('rules.deletion_protection') IN ('on', '')) OR
        (try_current_setting('rules.deletion_protection') IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();

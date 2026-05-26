--- Add missing index for deleted_at column, we include all related
--- columns because we expect the index to be small and expect use
--- index-only scans.
CREATE INDEX IF NOT EXISTS file_thumbnail__deleted_at__idx
    ON file_thumbnail (deleted_at, file_id, revn, media_id)
 WHERE deleted_at IS NOT NULL;

--- Add missing for media_id column, used mainly for refs checking
CREATE INDEX IF NOT EXISTS file_thumbnail__media_id__idx ON file_thumbnail (media_id);

--- Remove CASCADE from media_id and file_id foreign constraint
ALTER TABLE file_thumbnail
 DROP CONSTRAINT file_thumbnail_file_id_fkey;
ALTER TABLE file_thumbnail
  ADD CONSTRAINT file_thumbnail_file_id_fkey FOREIGN KEY (file_id) REFERENCES file(id) DEFERRABLE;

ALTER TABLE file_thumbnail
 DROP CONSTRAINT file_thumbnail_media_id_fkey;
ALTER TABLE file_thumbnail
  ADD CONSTRAINT file_thumbnail_media_id_fkey FOREIGN KEY (media_id) REFERENCES storage_object(id) DEFERRABLE;

--- Add deletion protection
DROP TRIGGER IF EXISTS deletion_protection__tgr ON file_thumbnail;
CREATE TRIGGER deletion_protection__tgr
BEFORE DELETE ON file_thumbnail FOR EACH STATEMENT
  WHEN ((try_current_setting('rules.deletion_protection') IN ('on', '')) OR
        (try_current_setting('rules.deletion_protection') IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();

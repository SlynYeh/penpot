ALTER TABLE file_tagged_object_thumbnail
  ADD COLUMN updated_at timestamptz NULL,
  ADD COLUMN deleted_at timestamptz NULL;

--- Add index for deleted_at column, we include all related columns
--- because we expect the index to be small and expect use index-only
--- scans.
CREATE INDEX IF NOT EXISTS file_tagged_object_thumbnail__deleted_at__idx
    ON file_tagged_object_thumbnail (deleted_at, file_id, object_id, media_id)
 WHERE deleted_at IS NOT NULL;

--- Remove CASCADE from media_id and file_id foreign constraint
ALTER TABLE file_tagged_object_thumbnail
 DROP CONSTRAINT file_tagged_object_thumbnail_media_id_fkey;
ALTER TABLE file_tagged_object_thumbnail
  ADD CONSTRAINT file_tagged_object_thumbnail_media_id_fkey FOREIGN KEY (media_id) REFERENCES storage_object(id) DEFERRABLE;

ALTER TABLE file_tagged_object_thumbnail
 DROP CONSTRAINT file_tagged_object_thumbnail_file_id_fkey;
ALTER TABLE file_tagged_object_thumbnail
  ADD CONSTRAINT file_tagged_object_thumbnail_file_id_fkey FOREIGN KEY (file_id) REFERENCES file(id) DEFERRABLE;

--- Add deletion protection
DROP TRIGGER IF EXISTS deletion_protection__tgr ON file_tagged_object_thumbnail;
CREATE TRIGGER deletion_protection__tgr
BEFORE DELETE ON file_tagged_object_thumbnail FOR EACH STATEMENT
  WHEN ((try_current_setting('rules.deletion_protection') IN ('on', '')) OR
        (try_current_setting('rules.deletion_protection') IS NULL))
  EXECUTE PROCEDURE raise_deletion_protection();

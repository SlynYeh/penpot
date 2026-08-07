ALTER TABLE file_media_object
RENAME CONSTRAINT media_object_file_id_fkey TO file_media_object_file_id_fkey;

ALTER TABLE file_media_object
 DROP CONSTRAINT file_media_object_media_id_fkey,
  ADD CONSTRAINT file_media_object_media_id_fkey
      FOREIGN KEY (media_id) REFERENCES storage_object(id) ON DELETE CASCADE DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE file_media_object
 DROP CONSTRAINT file_media_object_thumbnail_id_fkey,
  ADD CONSTRAINT file_media_object_thumbnail_id_fkey
      FOREIGN KEY (thumbnail_id) REFERENCES storage_object(id) ON DELETE SET NULL DEFERRABLE INITIALLY IMMEDIATE;

ALTER TABLE file_media_object
 DROP CONSTRAINT file_media_object_file_id_fkey,
  ADD CONSTRAINT file_media_object_file_id_fkey
      FOREIGN KEY (file_id) REFERENCES file(id) ON DELETE CASCADE DEFERRABLE INITIALLY IMMEDIATE;

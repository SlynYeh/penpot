ALTER TABLE file_change
 DROP CONSTRAINT file_change_file_id_fkey;
ALTER TABLE file_change
  ADD CONSTRAINT file_change_file_id_fkey FOREIGN KEY (file_id) REFERENCES file(id) DEFERRABLE;
ALTER TABLE file_change
 DROP CONSTRAINT file_change_profile_id_fkey;
ALTER TABLE file_change
  ADD CONSTRAINT file_change_profile_id_fkey FOREIGN KEY (profile_id) REFERENCES profile(id) ON DELETE SET NULL DEFERRABLE;

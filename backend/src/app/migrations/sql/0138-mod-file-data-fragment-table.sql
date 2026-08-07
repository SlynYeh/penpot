ALTER TABLE file_data_fragment
 DROP CONSTRAINT file_data_fragment_file_id_fkey,
  ADD CONSTRAINT file_data_fragment_file_id_fkey
      FOREIGN KEY (file_id) REFERENCES file(id) ON DELETE CASCADE DEFERRABLE INITIALLY DEFERRED;

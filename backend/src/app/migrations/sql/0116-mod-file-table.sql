ALTER TABLE file
 DROP CONSTRAINT file_project_id_fkey;
ALTER TABLE file
  ADD CONSTRAINT file_project_id_fkey FOREIGN KEY (project_id) REFERENCES project(id) DEFERRABLE;

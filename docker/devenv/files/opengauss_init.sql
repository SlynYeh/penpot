-- OpenGauss forbids the *initial* (initdb) user from remote/TCP connections;
-- only a non-initial user may connect over the network. The backend connects
-- over TCP, so we create `penpot` here as a non-initial SYSADMIN user: full
-- DDL/extension privileges for migrations, the credentials the backend expects,
-- and remote access is allowed. (The image's initial user `opengauss`, created
-- by its entrypoint regardless of GS_USERNAME, is left unused by the app.)
CREATE USER penpot WITH SYSADMIN PASSWORD 'rykpav-bosde4-noQwag';

CREATE DATABASE penpot_test;
CREATE DATABASE penpot_telemetry;

DROP TABLE slack;
DROP TABLE beeminder_goals;
DROP TABLE beeminder;
DELETE FROM pings WHERE user_id != 1;
DROP INDEX "pings_user_id_idx";
ALTER TABLE pings DROP CONSTRAINT pings_pkey;
ALTER TABLE pings DROP COLUMN user_id;
ALTER TABLE pings ADD PRIMARY KEY (ts);
DROP TABLE users;

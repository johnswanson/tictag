CREATE TABLE users
(
id BIGSERIAL PRIMARY KEY,
username VARCHAR(1024) NOT NULL UNIQUE,
email VARCHAR(1024) NOT NULL UNIQUE,
pass CHAR(98) NOT NULL,
tz VARCHAR(1024) NOT NULL UNIQUE
);

CREATE TABLE pings_backup AS SELECT * FROM pings;
DELETE FROM pings;
ALTER TABLE pings ADD user_id BIGINT REFERENCES users(id);
ALTER TABLE pings DROP CONSTRAINT pings_pkey;
ALTER TABLE pings ADD PRIMARY KEY (user_id, ts);
CREATE INDEX "pings_user_id_idx" ON pings (user_id);

CREATE TABLE slack
(
id BIGSERIAL PRIMARY KEY,
user_id BIGINT UNIQUE REFERENCES users(id),
slack_user_id VARCHAR(1024) NOT NULL,
username VARCHAR(1024),
encrypted_bot_access_token BYTEA,
encryption_iv BYTEA,
channel_id VARCHAR(1024)
);

CREATE TABLE beeminder
(
id BIGSERIAL PRIMARY KEY,
user_id BIGINT UNIQUE REFERENCES users(id),
username VARCHAR(1024),
encrypted_token BYTEA,
encryption_iv BYTEA,
is_enabled BOOLEAN
);

CREATE TABLE beeminder_goals
(
id BIGSERIAL PRIMARY KEY,
beeminder_id BIGINT REFERENCES beeminder(id),
goal VARCHAR(1024),
tags TEXT
);


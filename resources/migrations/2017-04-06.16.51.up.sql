CREATE TABLE users
(
id BIGSERIAL PRIMARY KEY,
username VARCHAR(1024) NOT NULL UNIQUE,
email VARCHAR(1024) NOT NULL UNIQUE,
pass CHAR(98) NOT NULL,
tz VARCHAR(1024) NOT NULL UNIQUE
);

INSERT INTO users
(
"username",
"email",
"pass",
"tz"
)
VALUES
(
'j',
'j@agh.io',
'bcrypt+sha512$bf75bd7e329fdd9c9b50614ce9142e10$12$56e7c0e1b0193b370ddf64961f8cef1f3320310ed0a2d7cf',
'America/Los_Angeles'
);

ALTER TABLE pings ADD user_id BIGINT REFERENCES users(id);
UPDATE pings SET user_id=1;
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

INSERT INTO beeminder
(user_id, username, encrypted_token, encryption_iv, is_enabled)
VALUES
(1, 'tictagtest', '\xbf967e499a49501c7c691b79837782ea568cf57be3c3bc268a57443d483c40b13284f975417c588d5e6ebf6ad3dae7a0', '\x3488f93aa3b2513813d9646040e8825b', TRUE);
INSERT INTO slack
(user_id, slack_user_id, username, encrypted_bot_access_token, encryption_iv, channel_id)
VALUES
(1, 'U0L05K5D3', 'john', '\x2c58a29dc1a872f3d32d14352b3bf158ad39e6e1d60dcac4acde41dc49fe80c824940626801ee3e34bfeb9b89f1ccb3971ee86a4db22514c57928ed90da185b4', '\x58402c1258619023e3c00f995b8643b0', 'D4HJSHY2K');

INSERT INTO beeminder_goals
(beeminder_id, goal, tags)
VALUES
(1, 'test', ':coding'),
(1, 'test2', '[:or :play :web]'),
(1, 'test3', '[:and :play :web]');

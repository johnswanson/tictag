DROP TABLE slack;
DROP TABLE beeminder_goals;
DROP TABLE beeminder;
ALTER TABLE pings RENAME TO pings_backup;
DROP TABLE users;
CREATE TABLE pings
(
ts BIGINT PRIMARY KEY,
tags VARCHAR(1024),
local_time VARCHAR(256),
calendar_event_id VARCHAR(256)
);

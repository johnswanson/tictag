ALTER TABLE pings ADD slack_ts CHAR(18);
CREATE INDEX ON pings (slack_ts);

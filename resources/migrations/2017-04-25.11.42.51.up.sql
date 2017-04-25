ALTER TABLE pings ADD tz_offset INTERVAL HOUR TO MINUTE;
UPDATE pings SET tz_offset = timezone(tz, ts) - ts;
ALTER TABLE pings ALTER COLUMN tz DROP NOT NULL;
ALTER TABLE pings ALTER COLUMN tz_offset SET NOT NULL;
ALTER TABLE beeminder_goals ADD CONSTRAINT beeminder_id_goal_key UNIQUE (beeminder_id, goal);

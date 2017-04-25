ALTER TABLE pings DROP tz_offset;
ALTER TABLE pings ALTER COLUMN tz SET NOT NULL;
ALTER TABLE beeminder_goals DROP CONSTRAINT beeminder_id_goal_key;

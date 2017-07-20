DROP TABLE macroexpansions;

ALTER TABLE beeminder_goals ADD beeminder_id BIGINT REFERENCES beeminder(id);

UPDATE beeminder_goals SET beeminder_id=beeminder.id
FROM beeminder
WHERE beeminder.user_id = beeminder_goals.user_id;

ALTER TABLE beeminder_goals DROP COLUMN user_id;
ALTER TABLE beeminder_goals ALTER COLUMN beeminder_id SET NOT NULL;

ALTER TABLE beeminder ALTER COLUMN is_enabled DROP DEFAULT;

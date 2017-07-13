CREATE TABLE macroexpansions
(
id BIGSERIAL PRIMARY KEY,
user_id BIGINT REFERENCES users(id) NOT NULL,
expands_from VARCHAR(1024) NOT NULL,
expands_to VARCHAR(1024) NOT NULL,
UNIQUE (user_id, expands_from)
);

CREATE INDEX ON macroexpansions (user_id);

ALTER TABLE beeminder_goals ADD user_id BIGINT REFERENCES users(id);

UPDATE beeminder_goals SET user_id=beeminder.user_id
FROM beeminder
WHERE beeminder.id = beeminder_goals.beeminder_id;

ALTER TABLE beeminder_goals DROP COLUMN beeminder_id;
ALTER TABLE beeminder_goals ALTER COLUMN user_id SET NOT NULL;


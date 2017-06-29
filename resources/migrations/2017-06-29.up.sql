CREATE TABLE macroexpansions
(
user_id BIGINT REFERENCES users(id) NOT NULL,
expands_from VARCHAR(1024) NOT NULL,
expands_to VARCHAR(1024) NOT NULL,
UNIQUE (user_id, expands_from)
);

CREATE INDEX ON macroexpansions (user_id);

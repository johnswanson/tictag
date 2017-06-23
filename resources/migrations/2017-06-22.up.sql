ALTER TABLE slack ADD dm_id VARCHAR(1024);
ALTER TABLE slack ADD use_dm BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE slack ADD use_channel BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE slack SET dm_id=channel_id;

ALTER TABLE slack ADD channel_name VARCHAR(1024);

ALTER TABLE slack ALTER COLUMN dm_id SET NOT NULL;
ALTER TABLE slack ALTER COLUMN channel_id DROP NOT NULL;

UPDATE slack SET channel_id=NULL;

CREATE TABLE ping_threads
(
ping_ts TIMESTAMP WITH TIME ZONE NOT NULL,
slack_ts CHAR(18) NOT NULL,
UNIQUE (ping_ts, slack_ts)
);

CREATE INDEX ON ping_threads (slack_ts);

INSERT INTO ping_threads (
SELECT pings.ts, pings.slack_ts
FROM pings
WHERE ts IS NOT NULL AND slack_ts IS NOT NULL
);




UPDATE slack SET channel_id = dm_id;
ALTER TABLE slack ALTER COLUMN channel_id SET NOT NULL;
ALTER TABLE slack DROP COLUMN dm_id;
ALTER TABLE slack DROP COLUMN use_dm;
ALTER TABLE slack DROP COLUMN use_channel;
ALTER TABLE slack DROP COLUMN channel_name;

DROP TABLE ping_threads;

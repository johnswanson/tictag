DELETE FROM ping_threads;
ALTER TABLE ping_threads
  DROP CONSTRAINT ping_threads_ping_ts_slack_ts_key;
ALTER TABLE ping_threads
  ADD COLUMN slack_id BIGINT REFERENCES slack(id) NOT NULL;
ALTER TABLE ping_threads
  ADD CONSTRAINT ping_threads_ping_ts_slack_id_ping_ts_key UNIQUE(ping_ts, slack_ts, slack_id);


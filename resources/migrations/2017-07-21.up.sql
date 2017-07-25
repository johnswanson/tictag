ALTER TABLE pings ADD id BIGSERIAL;
CREATE INDEX pings_id_idx ON pings(id);

CREATE TABLE IF NOT EXISTS listens (
  id TEXT PRIMARY KEY,
  track_key TEXT NOT NULL,
  media_id TEXT,
  title TEXT NOT NULL,
  artist TEXT NOT NULL,
  album TEXT,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  listened_ms INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  last_position_ms INTEGER NOT NULL DEFAULT 0,
  completed INTEGER NOT NULL DEFAULT 0,
  device_id TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS playback_events (
  listen_id TEXT NOT NULL,
  client_id INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  occurred_at INTEGER NOT NULL,
  position_ms INTEGER NOT NULL DEFAULT 0,
  payload TEXT,
  PRIMARY KEY (listen_id, client_id),
  FOREIGN KEY (listen_id) REFERENCES listens(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_listens_started ON listens(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_listens_track ON listens(track_key, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_time ON playback_events(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_events_type ON playback_events(event_type, occurred_at DESC);


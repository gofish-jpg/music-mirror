CREATE TABLE IF NOT EXISTS recommendation_sets (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  summary TEXT,
  created_at INTEGER NOT NULL,
  source TEXT NOT NULL DEFAULT 'chatgpt'
);

CREATE TABLE IF NOT EXISTS recommendations (
  id TEXT PRIMARY KEY,
  set_id TEXT NOT NULL,
  position INTEGER NOT NULL,
  title TEXT NOT NULL,
  artist TEXT NOT NULL,
  album TEXT,
  reason TEXT,
  youtube_url TEXT,
  created_at INTEGER NOT NULL,
  FOREIGN KEY (set_id) REFERENCES recommendation_sets(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS recommendation_feedback (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  recommendation_id TEXT NOT NULL,
  feedback TEXT NOT NULL CHECK (feedback IN ('like', 'dislike', 'played')),
  created_at INTEGER NOT NULL,
  device_id TEXT,
  FOREIGN KEY (recommendation_id) REFERENCES recommendations(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_recommendation_sets_created
  ON recommendation_sets(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_recommendations_set
  ON recommendations(set_id, position);
CREATE INDEX IF NOT EXISTS idx_recommendation_feedback_item
  ON recommendation_feedback(recommendation_id, created_at DESC);

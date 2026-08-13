PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS teams (
  team_code TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  auth_verifier TEXT NOT NULL,
  encryption_salt TEXT NOT NULL,
  created_at TEXT NOT NULL,
  failed_attempts INTEGER NOT NULL DEFAULT 0,
  locked_until TEXT
);

CREATE TABLE IF NOT EXISTS devices (
  team_code TEXT NOT NULL,
  device_id TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  name TEXT NOT NULL,
  platform TEXT NOT NULL,
  role TEXT NOT NULL CHECK(role IN ('admin', 'member')),
  active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  last_seen TEXT NOT NULL,
  last_ack_at TEXT,
  PRIMARY KEY (team_code, device_id),
  FOREIGN KEY (team_code) REFERENCES teams(team_code) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_devices_token_hash ON devices(token_hash);
CREATE INDEX IF NOT EXISTS idx_devices_team_active ON devices(team_code, active);

CREATE TABLE IF NOT EXISTS cloud_blobs (
  team_code TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('record', 'archive', 'settings')),
  owner_id TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  byte_length INTEGER NOT NULL,
  chunk_count INTEGER NOT NULL,
  sha256 TEXT NOT NULL,
  complete INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  PRIMARY KEY (team_code, blob_id),
  FOREIGN KEY (team_code) REFERENCES teams(team_code) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS cloud_blob_chunks (
  team_code TEXT NOT NULL,
  blob_id TEXT NOT NULL,
  chunk_index INTEGER NOT NULL,
  encrypted_data TEXT NOT NULL,
  PRIMARY KEY (team_code, blob_id, chunk_index),
  FOREIGN KEY (team_code, blob_id) REFERENCES cloud_blobs(team_code, blob_id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS records (
  team_code TEXT NOT NULL,
  record_id TEXT NOT NULL,
  record_date TEXT NOT NULL,
  type_id TEXT NOT NULL,
  type_name TEXT NOT NULL,
  version INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  status TEXT NOT NULL CHECK(status IN ('active', 'trash', 'archived')),
  payload_blob_id TEXT,
  archive_blob_id TEXT,
  archive_page_start INTEGER NOT NULL DEFAULT 0,
  archive_page_count INTEGER NOT NULL DEFAULT 0,
  source_device_id TEXT NOT NULL,
  deleted_at TEXT,
  server_changed_at TEXT NOT NULL,
  PRIMARY KEY (team_code, record_id),
  FOREIGN KEY (team_code) REFERENCES teams(team_code) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_records_team_date ON records(team_code, record_date);
CREATE INDEX IF NOT EXISTS idx_records_team_status ON records(team_code, status);

CREATE TABLE IF NOT EXISTS shared_settings (
  team_code TEXT NOT NULL,
  setting_key TEXT NOT NULL,
  version INTEGER NOT NULL,
  updated_at TEXT NOT NULL,
  payload_blob_id TEXT NOT NULL,
  source_device_id TEXT NOT NULL,
  server_changed_at TEXT NOT NULL,
  PRIMARY KEY (team_code, setting_key),
  FOREIGN KEY (team_code) REFERENCES teams(team_code) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS holiday_cache (
  year INTEGER PRIMARY KEY,
  payload TEXT NOT NULL,
  source_url TEXT NOT NULL,
  fetched_at TEXT NOT NULL
);

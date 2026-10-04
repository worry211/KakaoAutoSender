PRAGMA foreign_keys = ON;
CREATE TABLE licenses (
 license_id TEXT PRIMARY KEY,
 status TEXT NOT NULL CHECK(status IN ('UNUSED','ACTIVE','SUSPENDED','REVOKED','DELETED')),
 duration_seconds INTEGER CHECK(duration_seconds > 0),
 created_at INTEGER NOT NULL, created_by_admin_id TEXT NOT NULL,
 activated_at INTEGER, expires_at INTEGER,
 public_key TEXT, fingerprint TEXT,
 last_seen_at INTEGER, customer_memo TEXT NOT NULL DEFAULT '', admin_memo TEXT NOT NULL DEFAULT '',
 suspended_at INTEGER, suspended_by TEXT, revoked_at INTEGER, revoked_by TEXT, deleted_at INTEGER,
 device_reset_count INTEGER NOT NULL DEFAULT 0, last_device_reset_at INTEGER,
 generation INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 0,
 updated_at INTEGER NOT NULL, last_request TEXT NOT NULL,
 CHECK((public_key IS NULL) = (fingerprint IS NULL))
);
CREATE TABLE redeem_keys (
 key_hash TEXT PRIMARY KEY, license_id TEXT NOT NULL REFERENCES licenses(license_id),
 created_at INTEGER NOT NULL, consumed_at INTEGER, retired_at INTEGER, claim_id TEXT UNIQUE
);
CREATE UNIQUE INDEX one_unused_key ON redeem_keys(license_id) WHERE consumed_at IS NULL AND retired_at IS NULL;
CREATE TABLE sessions (
 session_id TEXT PRIMARY KEY, license_id TEXT NOT NULL REFERENCES licenses(license_id),
 generation INTEGER NOT NULL, access_hash TEXT UNIQUE NOT NULL, refresh_hash TEXT UNIQUE NOT NULL,
 access_expires_at INTEGER NOT NULL, refresh_expires_at INTEGER NOT NULL,
 revoked INTEGER NOT NULL DEFAULT 0 CHECK(revoked IN (0,1)), updated_at INTEGER NOT NULL
);
CREATE TABLE audit_events (
 event_id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, admin_discord_id TEXT NOT NULL,
 action TEXT NOT NULL, license_id TEXT, reason TEXT NOT NULL,
 before_state TEXT NOT NULL, after_state TEXT NOT NULL, request_id TEXT NOT NULL
);
CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit_events BEGIN SELECT RAISE(ABORT,'immutable audit'); END;
CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit_events BEGIN SELECT RAISE(ABORT,'immutable audit'); END;
CREATE INDEX licenses_status ON licenses(status);
CREATE INDEX licenses_expiry ON licenses(expires_at);
CREATE INDEX licenses_seen ON licenses(last_seen_at);
CREATE INDEX licenses_created ON licenses(created_at);
CREATE INDEX keys_license ON redeem_keys(license_id);
CREATE INDEX sessions_license ON sessions(license_id);
CREATE INDEX audit_license ON audit_events(license_id,timestamp);
CREATE TABLE config (
 id INTEGER PRIMARY KEY CHECK(id=1), maintenance INTEGER NOT NULL DEFAULT 0,
 kill_switch INTEGER NOT NULL DEFAULT 0, message TEXT NOT NULL DEFAULT '',
 min_version INTEGER NOT NULL DEFAULT 20, latest_version INTEGER NOT NULL DEFAULT 20,
 download_url TEXT NOT NULL DEFAULT '', release_notes TEXT NOT NULL DEFAULT '',
 heartbeat_seconds INTEGER NOT NULL DEFAULT 60 CHECK(heartbeat_seconds BETWEEN 30 AND 300),
 grace_seconds INTEGER NOT NULL DEFAULT 600 CHECK(grace_seconds BETWEEN 0 AND 600),
 revision INTEGER NOT NULL DEFAULT 0, last_request TEXT NOT NULL DEFAULT ''
);
INSERT INTO config(id) VALUES(1);
CREATE TABLE request_nonces (nonce_hash TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
CREATE TABLE rate_buckets (bucket TEXT PRIMARY KEY, count INTEGER NOT NULL, expires_at INTEGER NOT NULL);
CREATE TABLE interactions (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL);
CREATE TABLE confirmations (
 id TEXT PRIMARY KEY, admin_id TEXT NOT NULL, payload TEXT NOT NULL,
 expires_at INTEGER NOT NULL, consumed INTEGER NOT NULL DEFAULT 0
);

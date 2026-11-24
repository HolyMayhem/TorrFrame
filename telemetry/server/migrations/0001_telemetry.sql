CREATE TABLE totals (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  installations INTEGER NOT NULL DEFAULT 0
);
INSERT INTO totals(singleton) VALUES(1);

CREATE TABLE installations (
  id TEXT PRIMARY KEY,
  first_day TEXT NOT NULL,
  last_seen INTEGER NOT NULL
) WITHOUT ROWID;
CREATE INDEX installations_last_seen ON installations(last_seen);

CREATE TABLE daily (
  day TEXT PRIMARY KEY,
  active INTEGER NOT NULL,
  new_installs INTEGER NOT NULL,
  last_received INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE activity (
  id TEXT NOT NULL,
  day TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  version TEXT NOT NULL,
  build INTEGER NOT NULL,
  api INTEGER NOT NULL,
  manufacturer TEXT NOT NULL,
  model TEXT NOT NULL,
  architecture TEXT NOT NULL,
  ram TEXT NOT NULL,
  device TEXT NOT NULL,
  language TEXT NOT NULL,
  theme TEXT NOT NULL,
  country TEXT NOT NULL,
  report_day TEXT,
  report_mask INTEGER,
  PRIMARY KEY (day, id)
) WITHOUT ROWID;

CREATE TABLE feature_reports (
  id TEXT NOT NULL,
  day TEXT NOT NULL,
  mask INTEGER NOT NULL,
  PRIMARY KEY(day, id)
) WITHOUT ROWID;

CREATE TRIGGER count_installation AFTER INSERT ON installations BEGIN
  UPDATE totals SET installations = installations + 1 WHERE singleton = 1;
END;

CREATE TRIGGER aggregate_heartbeat AFTER INSERT ON activity BEGIN
  INSERT INTO installations(id, first_day, last_seen) VALUES(NEW.id, NEW.day, NEW.received_at)
  ON CONFLICT(id) DO UPDATE SET last_seen = excluded.last_seen;
  INSERT INTO daily(day, active, new_installs, last_received)
  VALUES(NEW.day, 1, (SELECT first_day = NEW.day FROM installations WHERE id = NEW.id), NEW.received_at)
  ON CONFLICT(day) DO UPDATE SET active = active + 1,
    new_installs = new_installs + excluded.new_installs, last_received = MAX(last_received, excluded.last_received);
  INSERT INTO feature_reports(id, day, mask) SELECT NEW.id, NEW.report_day, NEW.report_mask
  WHERE NEW.report_day IS NOT NULL ON CONFLICT(day, id) DO NOTHING;
END;

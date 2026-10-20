CREATE TABLE app_settings (
  id SMALLINT PRIMARY KEY,
  monthly_budget_usd DECIMAL(7,2) NOT NULL,
  warn_at_percent INTEGER NOT NULL,
  per_meeting_max_minutes INTEGER NOT NULL,
  recording_retention_days INTEGER,
  revision INTEGER NOT NULL DEFAULT 0,
  CONSTRAINT ck_app_settings_singleton CHECK (id = 1),
  CONSTRAINT ck_app_settings_budget CHECK (monthly_budget_usd >= 0 AND monthly_budget_usd <= 10000),
  CONSTRAINT ck_app_settings_warn CHECK (warn_at_percent BETWEEN 1 AND 100),
  CONSTRAINT ck_app_settings_max_minutes CHECK (per_meeting_max_minutes BETWEEN 1 AND 1440),
  CONSTRAINT ck_app_settings_retention CHECK (recording_retention_days IS NULL OR recording_retention_days BETWEEN 1 AND 3650)
);

INSERT INTO app_settings(id, monthly_budget_usd, warn_at_percent, per_meeting_max_minutes,
                         recording_retention_days, revision)
VALUES (1, 0.00, 80, 240, NULL, 0);

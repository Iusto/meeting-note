ALTER TABLE app_settings ADD COLUMN per_meeting_budget_usd DECIMAL(18,2) NOT NULL DEFAULT 3.00 CHECK (per_meeting_budget_usd > 0 AND per_meeting_budget_usd <= 10000);

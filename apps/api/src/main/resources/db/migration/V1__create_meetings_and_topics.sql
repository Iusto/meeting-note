CREATE TABLE meeting_topics (
  id UUID PRIMARY KEY,
  name VARCHAR(200) NOT NULL UNIQUE,
  description TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE meetings (
  id UUID PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  topic_id UUID REFERENCES meeting_topics(id),
  input_source VARCHAR(30) NOT NULL DEFAULT 'MOCK',
  status VARCHAR(30) NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE,
  ended_at TIMESTAMP WITH TIME ZONE,
  revision INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT meetings_revision_nonnegative CHECK (revision >= 0)
);

CREATE TABLE runtime_state (
  id INTEGER PRIMARY KEY,
  active_meeting_id UUID REFERENCES meetings(id),
  CONSTRAINT runtime_state_singleton CHECK (id = 1)
);
INSERT INTO runtime_state(id, active_meeting_id) VALUES (1, NULL);

CREATE UNIQUE INDEX only_one_started_meeting ON meetings ((1)) WHERE status = 'STARTED';

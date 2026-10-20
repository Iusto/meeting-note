CREATE TABLE active_deepgram_sessions (
  meeting_id UUID PRIMARY KEY REFERENCES meetings(id) ON DELETE CASCADE,
  stream_session_id UUID NOT NULL UNIQUE REFERENCES stream_sessions(id) ON DELETE CASCADE,
  request_id UUID NOT NULL UNIQUE REFERENCES ai_usage_records(id),
  claimed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

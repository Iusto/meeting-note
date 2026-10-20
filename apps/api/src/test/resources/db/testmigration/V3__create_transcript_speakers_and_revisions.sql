CREATE TABLE speakers (
  id UUID PRIMARY KEY, meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  provider_label VARCHAR(100) NOT NULL, display_name VARCHAR(200), review_state VARCHAR(30) NOT NULL DEFAULT 'TENTATIVE',
  UNIQUE (meeting_id, provider_label)
);
CREATE TABLE transcript_segments (
  id UUID PRIMARY KEY, meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  provider_event_id VARCHAR(200) NOT NULL, sequence_no BIGINT NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL,
  original_text TEXT NOT NULL, display_text TEXT NOT NULL, transcript_status VARCHAR(30) NOT NULL,
  speaker_id UUID REFERENCES speakers(id), speaker_status VARCHAR(30) NOT NULL, revision INTEGER NOT NULL DEFAULT 0,
  user_locked BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (meeting_id, provider_event_id), CHECK (start_ms >= 0 AND end_ms >= start_ms), CHECK (revision >= 0)
);
CREATE TABLE transcript_revisions (
  id UUID PRIMARY KEY, segment_id UUID NOT NULL REFERENCES transcript_segments(id) ON DELETE CASCADE,
  before_text TEXT NOT NULL, after_text TEXT NOT NULL, actor VARCHAR(30) NOT NULL, reason VARCHAR(300),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

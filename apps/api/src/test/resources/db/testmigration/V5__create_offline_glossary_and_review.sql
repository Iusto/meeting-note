CREATE TABLE glossary_terms (
  id UUID PRIMARY KEY, canonical VARCHAR(200) NOT NULL, normalized_canonical VARCHAR(200) NOT NULL UNIQUE,
  meaning TEXT NOT NULL, status VARCHAR(20) NOT NULL, source VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (status IN ('PENDING','APPROVED','REJECTED')), CHECK (source IN ('MANUAL','MOCK'))
);
CREATE TABLE glossary_aliases (
  id UUID PRIMARY KEY, term_id UUID NOT NULL REFERENCES glossary_terms(id) ON DELETE CASCADE,
  alias VARCHAR(200) NOT NULL, normalized_alias VARCHAR(200) NOT NULL UNIQUE,
  approved_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE topic_terms (
  topic_id UUID NOT NULL REFERENCES meeting_topics(id) ON DELETE CASCADE,
  term_id UUID NOT NULL REFERENCES glossary_terms(id) ON DELETE CASCADE,
  priority INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (topic_id, term_id)
);
CREATE TABLE meeting_summaries (
  id UUID PRIMARY KEY, meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  version INTEGER NOT NULL, provider VARCHAR(30) NOT NULL, content_json TEXT NOT NULL, snapshot_json TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE (meeting_id, version)
);
CREATE TABLE correction_suggestions (
  id UUID PRIMARY KEY, meeting_id UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  segment_id UUID NOT NULL REFERENCES transcript_segments(id) ON DELETE CASCADE,
  expected_revision INTEGER NOT NULL, span_start INTEGER NOT NULL, original_span TEXT NOT NULL,
  replacement TEXT NOT NULL, reason TEXT NOT NULL, state VARCHAR(20) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CHECK (state IN ('PENDING','APPLIED','REJECTED')),
  UNIQUE (segment_id, expected_revision, span_start, original_span, replacement)
);

ALTER TABLE recordings ADD COLUMN mime_type VARCHAR(120);
ALTER TABLE recordings ADD COLUMN chunk_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE recordings ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE recordings ADD CONSTRAINT uq_recordings_meeting UNIQUE (meeting_id);

CREATE TABLE recording_chunks (
  recording_id UUID NOT NULL REFERENCES recordings(id) ON DELETE CASCADE,
  sequence_no INTEGER NOT NULL, start_ms BIGINT NOT NULL, end_ms BIGINT NOT NULL,
  sha256 VARCHAR(64) NOT NULL, mime_type VARCHAR(120) NOT NULL, bytes BIGINT NOT NULL, path TEXT NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (recording_id, sequence_no),
  CONSTRAINT chk_recording_chunks_sequence CHECK (sequence_no >= 0),
  CONSTRAINT chk_recording_chunks_time CHECK (start_ms >= 0 AND end_ms >= start_ms),
  CONSTRAINT chk_recording_chunks_bytes CHECK (bytes >= 0)
);

ALTER TABLE speakers ADD COLUMN merged_into_label VARCHAR(100);
ALTER TABLE speakers ADD CONSTRAINT speaker_merge_not_self CHECK (merged_into_label IS NULL OR merged_into_label <> provider_label);
CREATE TABLE segment_speaker_overrides (
  segment_id UUID PRIMARY KEY REFERENCES transcript_segments(id) ON DELETE CASCADE,
  speaker_label VARCHAR(100) NOT NULL
);

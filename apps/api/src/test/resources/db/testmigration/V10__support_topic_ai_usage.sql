ALTER TABLE ai_usage_records ALTER COLUMN meeting_id DROP NOT NULL;
ALTER TABLE ai_usage_records ADD COLUMN topic_id UUID;
ALTER TABLE ai_usage_records ADD CONSTRAINT ck_ai_usage_subject
  CHECK ((meeting_id IS NOT NULL AND topic_id IS NULL) OR (meeting_id IS NULL AND topic_id IS NOT NULL));
CREATE INDEX ix_ai_usage_topic ON ai_usage_records(topic_id);
ALTER TABLE glossary_terms DROP CONSTRAINT chk_glossary_term_source;
ALTER TABLE glossary_terms ADD CONSTRAINT chk_glossary_term_source CHECK (source IN ('MANUAL','MOCK','OPENAI'));

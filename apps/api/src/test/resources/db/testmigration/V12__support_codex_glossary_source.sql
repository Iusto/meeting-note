ALTER TABLE glossary_terms DROP CONSTRAINT chk_glossary_term_source;
ALTER TABLE glossary_terms ADD CONSTRAINT chk_glossary_term_source CHECK (source IN ('MANUAL','MOCK','OPENAI','CODEX'));

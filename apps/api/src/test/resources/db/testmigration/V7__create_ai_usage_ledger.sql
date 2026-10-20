CREATE TABLE ai_usage_records (
  id UUID PRIMARY KEY,
  meeting_id UUID NOT NULL,
  provider VARCHAR(40) NOT NULL,
  model VARCHAR(100) NOT NULL,
  operation VARCHAR(60) NOT NULL,
  price_version VARCHAR(100) NOT NULL,
  state VARCHAR(20) NOT NULL,
  reservation_month DATE NOT NULL,
  reserved_usd DECIMAL(18,6) NOT NULL,
  estimated_usd DECIMAL(18,6), actual_usd DECIMAL(18,6), actual_source VARCHAR(100),
  maximum_audio_seconds BIGINT NOT NULL, measured_audio_seconds BIGINT,
  input_tokens BIGINT, output_tokens BIGINT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CHECK (state IN ('RESERVED','DISPATCHED','UNCERTAIN','SETTLED','RELEASED')),
  CHECK (reserved_usd > 0), CHECK (estimated_usd IS NULL OR estimated_usd >= 0),
  CHECK (actual_usd IS NULL OR actual_usd >= 0),
  CHECK ((actual_usd IS NULL AND actual_source IS NULL) OR (actual_usd IS NOT NULL AND actual_source IS NOT NULL)),
  CHECK (maximum_audio_seconds > 0), CHECK (measured_audio_seconds IS NULL OR measured_audio_seconds >= 0),
  CHECK (input_tokens IS NULL OR input_tokens >= 0), CHECK (output_tokens IS NULL OR output_tokens >= 0),
  CHECK ((state = 'SETTLED' AND estimated_usd IS NOT NULL) OR
         (state = 'UNCERTAIN' AND estimated_usd IS NULL AND measured_audio_seconds IS NULL AND
          input_tokens IS NULL AND output_tokens IS NULL) OR
         (state IN ('RESERVED','DISPATCHED','RELEASED') AND estimated_usd IS NULL AND actual_usd IS NULL AND
          actual_source IS NULL AND measured_audio_seconds IS NULL AND input_tokens IS NULL AND output_tokens IS NULL))
);
CREATE INDEX ix_ai_usage_month ON ai_usage_records(reservation_month);
CREATE INDEX ix_ai_usage_meeting_month ON ai_usage_records(meeting_id, reservation_month);

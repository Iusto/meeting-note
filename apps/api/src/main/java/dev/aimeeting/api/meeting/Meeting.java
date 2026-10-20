package dev.aimeeting.api.meeting;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Meeting(UUID id, String title, String status, String inputSource, UUID topicId,
                      OffsetDateTime startedAt, OffsetDateTime endedAt, int revision) {}

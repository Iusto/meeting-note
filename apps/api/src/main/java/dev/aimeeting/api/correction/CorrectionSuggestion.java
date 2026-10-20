package dev.aimeeting.api.correction;

import java.util.UUID;

public record CorrectionSuggestion(UUID id, UUID segmentId, int expectedRevision, String originalSpan,
                                   String replacement, String reason, String state) {}

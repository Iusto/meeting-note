package dev.aimeeting.api.summary;

import dev.aimeeting.api.provider.SummaryProvider;
import java.util.UUID;

public record MeetingSummary(UUID id, UUID meetingId, int version, String provider,
                             SummaryProvider.Summary content, boolean stale) {}

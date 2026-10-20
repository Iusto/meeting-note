package dev.aimeeting.api.summary;

import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.provider.SummaryProvider;
import dev.aimeeting.api.summary.SummaryRepository.SnapshotEntry;
import dev.aimeeting.api.summary.SummaryRepository.StoredSummary;
import dev.aimeeting.api.web.ApiException;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SummaryService {
    private final MeetingRepository meetings;
    private final SummaryRepository summaries;
    private final SummaryProvider provider;

    public SummaryService(MeetingRepository meetings, SummaryRepository summaries, SummaryProvider provider) {
        this.meetings = meetings;
        this.summaries = summaries;
        this.provider = provider;
    }

    public MeetingSummary latest(UUID meetingId) {
        meetings.find(meetingId);
        StoredSummary stored = summaries.latest(meetingId);
        return view(stored, snapshot(meetings.transcript(meetingId)));
    }

    public MeetingSummary latestOrNull(UUID meetingId) {
        StoredSummary stored = summaries.latestOrNull(meetingId);
        return stored == null ? null : view(stored, snapshot(meetings.transcript(meetingId)));
    }

    @Transactional
    public MeetingSummary generate(UUID meetingId) {
        summaries.lockMeeting(meetingId);
        Meeting meeting = meetings.find(meetingId);
        if (!"STOPPED".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_NOT_STOPPED", "Meeting must be stopped before summary generation");
        }
        List<TranscriptSegment> transcript = meetings.transcript(meetingId);
        if (transcript.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_FINALIZED", "A finalized transcript is required");
        }
        SummaryProvider.Summary content = provider.summarize(meetingId, transcript);
        validateEvidence(content, transcript);
        StoredSummary stored = summaries.insert(meetingId, summaries.nextVersion(meetingId), content, snapshot(transcript));
        return view(stored, snapshot(meetings.transcript(meetingId)));
    }

    public AiSnapshot prepareAiGeneration(UUID meetingId) {
        Meeting meeting = meetings.find(meetingId);
        requireStoppedMeeting(meeting);
        List<TranscriptSegment> transcript = meetings.transcript(meetingId);
        requireTranscript(transcript);
        return new AiSnapshot(meetingId, List.copyOf(transcript), snapshot(transcript));
    }

    @Transactional
    public MeetingSummary storeAiGenerated(AiSnapshot expected, SummaryProvider.Summary content) {
        summaries.lockMeeting(expected.meetingId());
        Meeting meeting = meetings.find(expected.meetingId());
        requireStoppedMeeting(meeting);
        List<TranscriptSegment> current = meetings.transcript(expected.meetingId());
        List<SnapshotEntry> currentSnapshot = snapshot(current);
        if (!current.equals(expected.transcript()) || !currentSnapshot.equals(expected.snapshot())
                || !snapshot(expected.transcript()).equals(expected.snapshot())) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_AI_SNAPSHOT",
                    "Transcript changed while the summary was being generated");
        }
        validateEvidence(content, current);
        StoredSummary stored = summaries.insert(expected.meetingId(), summaries.nextVersion(expected.meetingId()),
                "OPENAI", content, currentSnapshot);
        return view(stored, currentSnapshot);
    }

    private void requireStoppedMeeting(Meeting meeting) {
        if (!"STOPPED".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_NOT_STOPPED", "Meeting must be stopped before summary generation");
        }
    }

    private void requireTranscript(List<TranscriptSegment> transcript) {
        if (transcript.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_FINALIZED", "A finalized transcript is required");
        }
    }

    private void validateEvidence(SummaryProvider.Summary content, List<TranscriptSegment> transcript) {
        HashSet<UUID> segmentIds = new HashSet<>(transcript.stream().map(TranscriptSegment::id).toList());
        if ((!content.summary().isBlank() && content.sourceSegmentIds().isEmpty())
                || !segmentIds.containsAll(content.sourceSegmentIds())) invalidEvidence();
        content.decisions().forEach(item -> {
            if (item.sourceSegmentIds().isEmpty() || !segmentIds.containsAll(item.sourceSegmentIds())) invalidEvidence();
        });
        content.actionItems().forEach(item -> {
            if (item.sourceSegmentIds().isEmpty() || !segmentIds.containsAll(item.sourceSegmentIds())) invalidEvidence();
        });
        content.unresolved().forEach(item -> {
            if (item.sourceSegmentIds().isEmpty() || !segmentIds.containsAll(item.sourceSegmentIds())) invalidEvidence();
        });
    }

    private void invalidEvidence() {
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_SUMMARY_EVIDENCE", "Summary evidence was invalid");
    }

    private List<SnapshotEntry> snapshot(List<TranscriptSegment> transcript) {
        return transcript.stream().map(segment -> new SnapshotEntry(segment.id(), segment.revision())).toList();
    }

    private MeetingSummary view(StoredSummary stored, List<SnapshotEntry> current) {
        return new MeetingSummary(stored.id(), stored.meetingId(), stored.version(), stored.provider(), stored.content(),
                !stored.snapshot().equals(current));
    }

    public record AiSnapshot(UUID meetingId, List<TranscriptSegment> transcript, List<SnapshotEntry> snapshot) {}
}

package dev.aimeeting.api.correction;

import dev.aimeeting.api.correction.CorrectionRepository.StoredCorrection;
import dev.aimeeting.api.meeting.Meeting;
import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.provider.openai.MeetingAiPayloads.CorrectionPatch;
import dev.aimeeting.api.topic.TopicRepository;
import dev.aimeeting.api.topic.TopicRepository.CorrectionAlias;
import dev.aimeeting.api.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CorrectionService {
    private static final String AMBIGUOUS_LOTTERY_ALIAS = "로또";
    private static final Pattern NUMBER = Pattern.compile("\\p{N}");
    private final MeetingRepository meetings;
    private final TopicRepository topics;
    private final CorrectionRepository corrections;

    public CorrectionService(MeetingRepository meetings, TopicRepository topics, CorrectionRepository corrections) {
        this.meetings = meetings;
        this.topics = topics;
        this.corrections = corrections;
    }

    @Transactional
    public List<CorrectionSuggestion> generate(UUID meetingId) {
        Meeting meeting = meetings.find(meetingId);
        if (!"STOPPED".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_NOT_STOPPED", "Meeting must be stopped before correction review");
        }
        if (meeting.topicId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_TOPIC_REQUIRED", "Meeting topic is required for glossary corrections");
        }
        List<TranscriptSegment> transcript = meetings.transcript(meetingId);
        if (transcript.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_FINALIZED", "A finalized transcript is required");
        }
        List<CorrectionAlias> aliases = topics.approvedAliases(meeting.topicId()).stream()
                .filter(this::safeAlias).toList();
        ArrayList<CorrectionSuggestion> generated = new ArrayList<>();
        for (TranscriptSegment segment : transcript) {
            if (segment.userLocked()) continue;
            for (CorrectionAlias alias : aliases) {
                Matcher matcher = Pattern.compile("(?<![\\p{L}\\p{N}_-])" + Pattern.quote(alias.alias())
                                + "(?![\\p{L}\\p{N}_-])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                        .matcher(segment.displayText());
                while (matcher.find()) {
                    generated.add(corrections.insert(meetingId, segment.id(), segment.revision(), matcher.start(),
                            matcher.group(), alias.canonical(), "승인된 주제 용어 별칭과 일치"));
                }
            }
        }
        return generated;
    }

    public AiSnapshot prepareAiGeneration(UUID meetingId) {
        Meeting meeting = meetings.find(meetingId);
        requireStoppedMeeting(meeting);
        if (meeting.topicId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_TOPIC_REQUIRED", "Meeting topic is required for glossary corrections");
        }
        List<TranscriptSegment> transcript = meetings.transcript(meetingId);
        requireTranscript(transcript);
        List<CorrectionAlias> aliases = safeAliases(meeting.topicId());
        return new AiSnapshot(meetingId, meeting.topicId(), List.copyOf(transcript), aliases);
    }

    @Transactional
    public List<CorrectionSuggestion> storeAiGenerated(AiSnapshot expected, List<CorrectionPatch> patches) {
        Meeting currentMeeting = meetings.lock(expected.meetingId());
        requireStoppedMeeting(currentMeeting);
        List<TranscriptSegment> currentTranscript = meetings.transcript(expected.meetingId());
        List<CorrectionAlias> currentAliases = expected.topicId().equals(currentMeeting.topicId())
                ? safeAliases(expected.topicId()) : List.of();
        if (!expected.topicId().equals(currentMeeting.topicId())
                || !expected.transcript().equals(currentTranscript)
                || !expected.aliases().equals(currentAliases)) {
            throw new ApiException(HttpStatus.CONFLICT, "STALE_AI_SNAPSHOT",
                    "Transcript or glossary changed while corrections were being generated");
        }
        ArrayList<CorrectionSuggestion> stored = new ArrayList<>();
        for (CorrectionPatch patch : patches) {
            validateAiPatch(patch, currentTranscript, currentAliases);
            stored.add(corrections.insert(expected.meetingId(), patch.segmentId(), patch.expectedRevision(),
                    patch.spanStart(), patch.originalSpan(), patch.replacement(), patch.reason()));
        }
        return List.copyOf(stored);
    }

    private void validateAiPatch(CorrectionPatch patch, List<TranscriptSegment> transcript,
                                 List<CorrectionAlias> aliases) {
        TranscriptSegment segment = transcript.stream().filter(item -> item.id().equals(patch.segmentId()))
                .findFirst().orElseThrow(this::invalidAiCorrection);
        int end;
        try {
            end = Math.addExact(patch.spanStart(), patch.originalSpan().length());
        } catch (RuntimeException invalid) {
            throw invalidAiCorrection();
        }
        boolean approved = aliases.stream().anyMatch(alias -> normalize(alias.alias()).equals(normalize(patch.originalSpan()))
                && alias.canonical().equals(patch.replacement()));
        if (!patch.requiresReview() || segment.userLocked() || segment.revision() != patch.expectedRevision()
                || patch.spanStart() < 0 || end > segment.displayText().length()
                || !segment.displayText().substring(patch.spanStart(), end).equals(patch.originalSpan()) || !approved) {
            throw invalidAiCorrection();
        }
    }

    private ApiException invalidAiCorrection() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AI_CORRECTION", "AI correction was invalid");
    }

    public List<CorrectionSuggestion> list(UUID meetingId) {
        meetings.find(meetingId);
        return corrections.list(meetingId);
    }

    @Transactional
    public CorrectionSuggestion review(UUID meetingId, UUID suggestionId, String state) {
        if (!List.of("APPLIED", "REJECTED").contains(state)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CORRECTION_STATE", "Correction state must be APPLIED or REJECTED");
        }
        meetings.lock(meetingId);
        StoredCorrection suggestion = corrections.lock(meetingId, suggestionId);
        if (!"PENDING".equals(suggestion.state())) {
            if (state.equals(suggestion.state())) return corrections.setState(meetingId, suggestionId, state);
            throw new ApiException(HttpStatus.CONFLICT, "CORRECTION_ALREADY_REVIEWED", "Correction suggestion was already reviewed");
        }
        if ("REJECTED".equals(state)) return corrections.setState(meetingId, suggestionId, state);

        TranscriptSegment segment = meetings.findSegment(suggestion.segmentId());
        if (segment.userLocked() || segment.revision() != suggestion.expectedRevision()) stale();
        int start = suggestion.spanStart();
        int end = start + suggestion.originalSpan().length();
        if (start < 0 || end > segment.displayText().length()
                || !segment.displayText().substring(start, end).equals(suggestion.originalSpan())) stale();
        Meeting meeting = meetings.find(meetingId);
        if (meeting.topicId() == null || safeAliases(meeting.topicId()).stream().noneMatch(alias ->
                normalize(alias.alias()).equals(normalize(suggestion.originalSpan()))
                        && alias.canonical().equals(suggestion.replacement()))) stale();
        String corrected = segment.displayText().substring(0, start) + suggestion.replacement()
                + segment.displayText().substring(end);
        meetings.correct(meetingId, segment.id(), suggestion.expectedRevision(), corrected,
                "AI_REVIEW", suggestion.reason(), false);
        return corrections.setState(meetingId, suggestionId, "APPLIED");
    }

    private boolean safeAlias(CorrectionAlias alias) {
        return !alias.normalizedAlias().equals(AMBIGUOUS_LOTTERY_ALIAS)
                && !NUMBER.matcher(alias.normalizedAlias()).find()
                && !NUMBER.matcher(normalize(alias.canonical())).find()
                && !alias.alias().toLowerCase(Locale.ROOT).equals(alias.canonical().toLowerCase(Locale.ROOT));
    }

    private List<CorrectionAlias> safeAliases(UUID topicId) {
        return List.copyOf(topics.approvedAliases(topicId).stream().filter(this::safeAlias).toList());
    }

    private void requireStoppedMeeting(Meeting meeting) {
        if (!"STOPPED".equals(meeting.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "MEETING_NOT_STOPPED", "Meeting must be stopped before correction review");
        }
    }

    private void requireTranscript(List<TranscriptSegment> transcript) {
        if (transcript.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "TRANSCRIPT_NOT_FINALIZED", "A finalized transcript is required");
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private void stale() {
        throw new ApiException(HttpStatus.CONFLICT, "STALE_CORRECTION", "Transcript changed after this correction was proposed");
    }

    public record AiSnapshot(UUID meetingId, UUID topicId, List<TranscriptSegment> transcript,
                             List<CorrectionAlias> aliases) {}
}

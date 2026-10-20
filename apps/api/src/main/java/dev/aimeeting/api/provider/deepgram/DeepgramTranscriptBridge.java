package dev.aimeeting.api.provider.deepgram;

import dev.aimeeting.api.meeting.MeetingRepository;
import dev.aimeeting.api.meeting.MeetingRepository.FinalSegmentInput;
import dev.aimeeting.api.meeting.TranscriptSegment;
import dev.aimeeting.api.provider.SpeechEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Converts one Deepgram stream session's relative results into meeting timeline events. */
public final class DeepgramTranscriptBridge implements AutoCloseable {
    private final UUID meetingId;
    private final UUID streamSessionId;
    private final long audioOffsetMs;
    private final MeetingRepository meetings;
    private final Consumer<SpeechEvent> consumer;
    private final AtomicLong sequence;
    private boolean closed;

    public DeepgramTranscriptBridge(UUID meetingId, UUID streamSessionId, long audioOffsetMs,
                                    MeetingRepository meetings, Consumer<SpeechEvent> consumer) {
        this.meetingId = Objects.requireNonNull(meetingId, "meetingId");
        this.streamSessionId = Objects.requireNonNull(streamSessionId, "streamSessionId");
        if (audioOffsetMs < 0) throw new IllegalArgumentException("audioOffsetMs must be nonnegative");
        this.audioOffsetMs = audioOffsetMs;
        this.meetings = Objects.requireNonNull(meetings, "meetings");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.sequence = new AtomicLong(meetings.maxTranscriptSequence(meetingId));
    }

    public synchronized void accept(DeepgramMessage.Results result) {
        Objects.requireNonNull(result, "result");
        if (closed) throw new IllegalStateException("Deepgram transcript bridge is closed");
        if (result.transcript().isEmpty()) return;

        String baseEventId = eventId(result);
        if (meetings.transcriptEventExists(meetingId, baseEventId)) return;
        if (!result.finalResult()) {
            consumer.accept(partial(result, baseEventId));
            return;
        }

        List<SegmentPart> parts = segmentParts(result);
        List<FinalSegmentInput> inputs = new ArrayList<>(parts.size());
        for (int index = 0; index < parts.size(); index++) {
            String eventId = index == 0 ? baseEventId : baseEventId + ":" + index;
            SegmentPart part = parts.get(index);
            long nextSequence = sequence.incrementAndGet();
            inputs.add(new FinalSegmentInput(eventId, nextSequence, offset(part.startMs()), offset(part.endMs()),
                    part.text(), part.speakerLabel(), part.speakerStatus()));
        }
        List<TranscriptSegment> inserted = meetings.insertFinalBatchIfAbsent(meetingId, inputs);
        inserted.stream().map(this::finalEvent).forEach(consumer);
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private SpeechEvent partial(DeepgramMessage.Results result, String eventId) {
        String speaker = unambiguousSpeaker(result.words());
        String speakerStatus = speaker != null ? "TENTATIVE" : hasMultipleSpeakers(result.words()) ? "OVERLAP" : "UNKNOWN";
        return new SpeechEvent("transcript.partial", meetingId, streamSessionId, eventId,
                sequence.incrementAndGet(), offset(result.startMs()), offset(result.endMs()), result.transcript(),
                speaker, speakerStatus, null, 0, result.transcript(), result.transcript(), "PARTIAL");
    }

    private SpeechEvent finalEvent(TranscriptSegment segment) {
        return new SpeechEvent("transcript.final", meetingId, streamSessionId, segment.eventId(),
                segment.sequence(), segment.startMs(), segment.endMs(), segment.displayText(),
                segment.speakerLabel(), segment.speakerStatus(), segment.id(), segment.revision(),
                segment.originalText(), segment.displayText(), segment.transcriptStatus());
    }

    private List<SegmentPart> segmentParts(DeepgramMessage.Results result) {
        List<AlignedWord> aligned = align(result);
        if (aligned.isEmpty() || aligned.stream().anyMatch(word -> word.word().speaker() == null)) {
            String status = hasMultipleSpeakers(result.words()) ? "OVERLAP" : "UNKNOWN";
            return List.of(new SegmentPart(result.startMs(), result.endMs(), result.transcript(), null, status));
        }

        List<SegmentPart> parts = new ArrayList<>();
        int groupStart = 0;
        for (int index = 1; index <= aligned.size(); index++) {
            boolean boundary = index == aligned.size()
                    || !aligned.get(index).word().speaker().equals(aligned.get(groupStart).word().speaker());
            if (!boundary) continue;
            int textStart = groupStart == 0 ? 0 : aligned.get(groupStart).textStart();
            int textEnd = index == aligned.size() ? result.transcript().length() : aligned.get(index).textStart();
            long startMs = groupStart == 0 ? result.startMs() : aligned.get(groupStart).word().startMs();
            long endMs = index == aligned.size() ? result.endMs() : aligned.get(index).word().startMs();
            parts.add(new SegmentPart(startMs, endMs, result.transcript().substring(textStart, textEnd),
                    speakerLabel(aligned.get(groupStart).word().speaker()), "TENTATIVE"));
            groupStart = index;
        }
        return parts;
    }

    private List<AlignedWord> align(DeepgramMessage.Results result) {
        String transcript = result.transcript();
        List<DeepgramMessage.Word> words = result.words();
        if (words.isEmpty()) return List.of();
        List<AlignedWord> aligned = new ArrayList<>(words.size());
        int cursor = 0;
        long previousStartMs = Long.MIN_VALUE;
        long previousEndMs = Long.MIN_VALUE;
        for (DeepgramMessage.Word word : words) {
            if (word.text().isEmpty()) return List.of();
            if (word.startMs() < result.startMs() || word.endMs() > result.endMs()) return List.of();
            if (word.endMs() < word.startMs() || word.startMs() < previousStartMs || word.endMs() < previousEndMs) {
                return List.of();
            }
            int start = transcript.indexOf(word.text(), cursor);
            if (start < 0 || containsLetterOrDigit(transcript, cursor, start)) return List.of();
            aligned.add(new AlignedWord(word, start));
            cursor = start + word.text().length();
            previousStartMs = word.startMs();
            previousEndMs = word.endMs();
        }
        if (containsLetterOrDigit(transcript, cursor, transcript.length())) return List.of();
        return List.copyOf(aligned);
    }

    private boolean containsLetterOrDigit(String value, int start, int end) {
        return value.substring(start, end).codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private String unambiguousSpeaker(List<DeepgramMessage.Word> words) {
        if (words.isEmpty()) return null;
        Integer speaker = words.getFirst().speaker();
        if (speaker == null) return null;
        return words.stream().allMatch(word -> speaker.equals(word.speaker())) ? speakerLabel(speaker) : null;
    }

    private boolean hasMultipleSpeakers(List<DeepgramMessage.Word> words) {
        Set<Integer> speakers = new HashSet<>();
        for (DeepgramMessage.Word word : words) {
            if (word.speaker() != null) speakers.add(word.speaker());
        }
        return speakers.size() > 1;
    }

    private String speakerLabel(int providerSpeaker) {
        return "speaker_" + streamSessionId + "_" + providerSpeaker;
    }

    private String eventId(DeepgramMessage.Results result) {
        String channels = result.channelIndex().stream().map(String::valueOf)
                .reduce((left, right) -> left + "," + right).orElse("unknown");
        UUID channelHash = UUID.nameUUIDFromBytes(channels.getBytes(StandardCharsets.UTF_8));
        return "dg:" + streamSessionId + ":" + channelHash + ":" + result.startMs();
    }

    private long offset(long relativeMs) {
        try {
            return Math.addExact(relativeMs, audioOffsetMs);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Deepgram timestamp exceeds the meeting timeline", exception);
        }
    }

    private record AlignedWord(DeepgramMessage.Word word, int textStart) {}
    private record SegmentPart(long startMs, long endMs, String text, String speakerLabel, String speakerStatus) {}
}

package dev.aimeeting.api.provider.deepgram;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DeepgramMessageParser {
    public static final String SANITIZED_ERROR_CODE = "DEEPGRAM_ERROR";

    private static final String INVALID_MESSAGE = "Invalid Deepgram message";
    private static final int MAX_JSON_BYTES = 1_048_576;
    private static final int MAX_TYPE_CHARS = 64;
    private static final int MAX_CHANNELS = 16;
    private static final int MAX_TRANSCRIPT_CHARS = 100_000;
    private static final int MAX_WORDS = 10_000;
    private static final int MAX_WORD_CHARS = 1_000;
    private static final int MAX_REQUEST_ID_CHARS = 256;

    private final ObjectMapper mapper;

    public DeepgramMessageParser(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public DeepgramMessage parse(String json, long audioOffsetMs) {
        if (audioOffsetMs < 0 || json == null || json.length() > MAX_JSON_BYTES
                || utf8LengthExceeds(json, MAX_JSON_BYTES)) {
            throw invalidMessage();
        }
        try {
            JsonNode root = mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                    .readTree(json);
            if (root == null || !root.isObject()) {
                throw invalidMessage();
            }
            String type = requiredBoundedText(root, "type", MAX_TYPE_CHARS, false);
            if (hasControlCharacter(type)) {
                throw invalidMessage();
            }
            return switch (type) {
                case "Results" -> parseResults(root, audioOffsetMs);
                case "Metadata" -> new DeepgramMessage.Metadata(
                        requiredBoundedText(root, "request_id", MAX_REQUEST_ID_CHARS, false));
                case "Error" -> new DeepgramMessage.Error(SANITIZED_ERROR_CODE);
                default -> new DeepgramMessage.Unknown(type);
            };
        } catch (IllegalArgumentException exception) {
            throw invalidMessage();
        } catch (Exception exception) {
            throw invalidMessage();
        }
    }

    private DeepgramMessage.Results parseResults(JsonNode root, long audioOffsetMs) {
        List<Integer> channelIndex = parseChannelIndex(root.get("channel_index"));
        BigDecimal startSeconds = requiredTimestamp(root, "start");
        BigDecimal durationSeconds = requiredTimestamp(root, "duration");
        long startMs = timestampMs(startSeconds, audioOffsetMs);
        long endMs = timestampMs(startSeconds.add(durationSeconds), audioOffsetMs);
        if (endMs < startMs) {
            throw invalidMessage();
        }

        boolean finalResult = requiredBoolean(root, "is_final");
        boolean speechFinal = optionalBoolean(root, "speech_final");
        boolean fromFinalize = optionalBoolean(root, "from_finalize");

        JsonNode channel = root.get("channel");
        JsonNode alternatives = channel == null ? null : channel.get("alternatives");
        if (channel == null || !channel.isObject() || alternatives == null || !alternatives.isArray()
                || alternatives.isEmpty() || !alternatives.get(0).isObject()) {
            throw invalidMessage();
        }
        JsonNode alternative = alternatives.get(0);
        String transcript = requiredBoundedText(alternative, "transcript", MAX_TRANSCRIPT_CHARS, true);
        List<DeepgramMessage.Word> words = parseWords(alternative.get("words"), audioOffsetMs);
        return new DeepgramMessage.Results(channelIndex, startMs, endMs, transcript, finalResult,
                speechFinal, fromFinalize, words);
    }

    private List<Integer> parseChannelIndex(JsonNode node) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_CHANNELS) {
            throw invalidMessage();
        }
        List<Integer> channels = new ArrayList<>(node.size());
        for (JsonNode channel : node) {
            if (!channel.isIntegralNumber() || !channel.canConvertToInt() || channel.intValue() < 0) {
                throw invalidMessage();
            }
            channels.add(channel.intValue());
        }
        return List.copyOf(channels);
    }

    private List<DeepgramMessage.Word> parseWords(JsonNode node, long audioOffsetMs) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > MAX_WORDS) {
            throw invalidMessage();
        }
        List<DeepgramMessage.Word> words = new ArrayList<>(node.size());
        for (JsonNode word : node) {
            if (!word.isObject()) {
                throw invalidMessage();
            }
            String text = requiredBoundedText(word, "word", MAX_WORD_CHARS, true);
            long startMs = timestampMs(requiredTimestamp(word, "start"), audioOffsetMs);
            long endMs = timestampMs(requiredTimestamp(word, "end"), audioOffsetMs);
            if (endMs < startMs) {
                throw invalidMessage();
            }
            JsonNode speakerNode = word.get("speaker");
            Integer speaker = null;
            if (speakerNode != null && !speakerNode.isNull()) {
                if (!speakerNode.isIntegralNumber() || !speakerNode.canConvertToInt() || speakerNode.intValue() < 0) {
                    throw invalidMessage();
                }
                speaker = speakerNode.intValue();
            }
            words.add(new DeepgramMessage.Word(text, startMs, endMs, speaker));
        }
        return List.copyOf(words);
    }

    private BigDecimal requiredTimestamp(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isNumber()) {
            throw invalidMessage();
        }
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            throw invalidMessage();
        }
        BigDecimal value = node.decimalValue();
        if (value.signum() < 0) {
            throw invalidMessage();
        }
        return value;
    }

    private long timestampMs(BigDecimal seconds, long audioOffsetMs) {
        try {
            long relativeMs = seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return Math.addExact(relativeMs, audioOffsetMs);
        } catch (ArithmeticException exception) {
            throw invalidMessage();
        }
    }

    private boolean requiredBoolean(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || !node.isBoolean()) {
            throw invalidMessage();
        }
        return node.booleanValue();
    }

    private boolean optionalBoolean(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw invalidMessage();
        }
        return node.booleanValue();
    }

    private String requiredBoundedText(JsonNode object, String field, int maxChars, boolean allowEmpty) {
        JsonNode node = object.get(field);
        if (node == null || !node.isTextual()) {
            throw invalidMessage();
        }
        String value = node.textValue();
        if ((!allowEmpty && value.isBlank()) || value.length() > maxChars) {
            throw invalidMessage();
        }
        return value;
    }

    private boolean utf8LengthExceeds(String value, int maximum) {
        return value.getBytes(StandardCharsets.UTF_8).length > maximum;
    }

    private boolean hasControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }

    private IllegalArgumentException invalidMessage() {
        return new IllegalArgumentException(INVALID_MESSAGE);
    }
}

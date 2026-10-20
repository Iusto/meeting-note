package dev.aimeeting.api.provider.deepgram;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validated options for the offline Deepgram protocol preparation.
 *
 * <p>The 500-byte keyterm payload limit is an intentionally conservative local POC guard. Deepgram's
 * documented provider limit is 500 tokens, and this class does not claim to reproduce that tokenizer.
 */
public record DeepgramOptions(boolean diarization, List<String> keyterms, DeepgramAudioFormat audioFormat) {
    private static final int MAX_KEYTERMS = 100;
    private static final int MAX_KEYTERM_CHARS = 200;
    private static final int MAX_KEYTERM_UTF8_BYTES = 500;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    public DeepgramOptions(boolean diarization, List<String> keyterms) {
        this(diarization, keyterms, DeepgramAudioFormat.containerized());
    }

    public DeepgramOptions {
        Objects.requireNonNull(keyterms, "keyterms");
        Objects.requireNonNull(audioFormat, "audioFormat");
        if (keyterms.size() > MAX_KEYTERMS) {
            throw new IllegalArgumentException("Too many keyterms");
        }

        Set<String> distinct = new HashSet<>();
        int utf8Bytes = 0;
        for (String keyterm : keyterms) {
            if (keyterm == null || keyterm.isBlank()) {
                throw new IllegalArgumentException("Keyterms must be nonblank");
            }
            if (keyterm.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Keyterms must not contain control characters");
            }
            if (keyterm.length() > MAX_KEYTERM_CHARS) {
                throw new IllegalArgumentException("Keyterm is too long");
            }
            if (!distinct.add(keyterm)) {
                throw new IllegalArgumentException("Keyterms must be distinct");
            }
            utf8Bytes = Math.addExact(utf8Bytes, keyterm.getBytes(StandardCharsets.UTF_8).length);
            if (utf8Bytes > MAX_KEYTERM_UTF8_BYTES) {
                throw new IllegalArgumentException("Keyterm payload is too large");
            }
        }
        keyterms = List.copyOf(keyterms);
    }

    public String queryString() {
        StringBuilder query = new StringBuilder("model=nova-3&language=ko&interim_results=true");
        if (diarization) {
            query.append("&diarize_model=latest");
        }
        for (String keyterm : keyterms) {
            query.append("&keyterm=").append(encodeQueryValue(keyterm));
        }
        query.append(audioFormat.querySuffix());
        return query.toString();
    }

    private static String encodeQueryValue(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte rawByte : bytes) {
            int current = rawByte & 0xff;
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '.' || current == '_' || current == '~') {
                encoded.append((char) current);
            } else {
                encoded.append('%').append(HEX[current >>> 4]).append(HEX[current & 0x0f]);
            }
        }
        return encoded.toString();
    }
}

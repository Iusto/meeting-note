package dev.aimeeting.api.provider.deepgram;

/** Audio format parameters sent to Deepgram's live transcription endpoint. */
public final class DeepgramAudioFormat {
    private static final DeepgramAudioFormat CONTAINERIZED = new DeepgramAudioFormat("");
    private static final DeepgramAudioFormat PCM_16K_MONO = new DeepgramAudioFormat(
            "&encoding=linear16&sample_rate=16000&channels=1");

    private final String querySuffix;

    private DeepgramAudioFormat(String querySuffix) {
        this.querySuffix = querySuffix;
    }

    /** Lets Deepgram detect a supported container such as WebM or Ogg from its headers. */
    public static DeepgramAudioFormat containerized() {
        return CONTAINERIZED;
    }

    /** Declares headerless signed 16-bit little-endian PCM at 16 kHz, mono. */
    public static DeepgramAudioFormat pcm16kMono() {
        return PCM_16K_MONO;
    }

    public String querySuffix() {
        return querySuffix;
    }
}

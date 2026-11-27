package dev.aimeeting.api.provider;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface LiveSpeechSession {
    UUID streamSessionId();
    CompletableFuture<Void> sendAudio(byte[] pcm);
    CompletableFuture<?> finish();
    CompletableFuture<Outcome> outcome();
    void close();

    record Outcome(String reason, boolean retry) {}
}

package dev.aimeeting.api.provider;

import java.util.UUID;
import java.util.function.Consumer;

public interface SpeechProvider {
    void stream(UUID meetingId, UUID streamSessionId, Consumer<SpeechEvent> consumer);
    void stop(UUID streamSessionId);
    void stopMeeting(UUID meetingId);
}

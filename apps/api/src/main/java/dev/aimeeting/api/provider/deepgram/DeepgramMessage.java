package dev.aimeeting.api.provider.deepgram;

import java.util.List;

public sealed interface DeepgramMessage
        permits DeepgramMessage.Results, DeepgramMessage.Metadata, DeepgramMessage.Error, DeepgramMessage.Unknown {

    record Results(
            List<Integer> channelIndex,
            long startMs,
            long endMs,
            String transcript,
            boolean finalResult,
            boolean speechFinal,
            boolean fromFinalize,
            List<Word> words) implements DeepgramMessage {
        public Results {
            channelIndex = List.copyOf(channelIndex);
            words = List.copyOf(words);
        }
    }

    record Word(String text, long startMs, long endMs, Integer speaker) {}

    record Metadata(String requestId) implements DeepgramMessage {}

    record Error(String code) implements DeepgramMessage {}

    record Unknown(String type) implements DeepgramMessage {}
}

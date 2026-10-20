package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeepgramMessageParserTest {
    private final DeepgramMessageParser parser = new DeepgramMessageParser(new ObjectMapper());

    @Test
    void parsesFirstAlternativePartialWithMixedSpeakersAndOffset() {
        DeepgramMessage message = parser.parse("""
                {"type":"Results","channel_index":[0,1],"start":1.25,"duration":2.5,
                 "is_final":false,"speech_final":false,"from_finalize":false,
                 "channel":{"alternatives":[
                   {"transcript":"첫 후보","words":[
                     {"word":"첫","start":1.25,"end":1.5,"speaker":0},
                     {"word":"후보","start":1.5,"end":2.0,"speaker":1}]},
                   {"transcript":"무시할 후보","words":[]}]}}
                """, 10_000);

        assertThat(message).isEqualTo(new DeepgramMessage.Results(
                List.of(0, 1), 11_250, 13_750, "첫 후보", false, false, false,
                List.of(new DeepgramMessage.Word("첫", 11_250, 11_500, 0),
                        new DeepgramMessage.Word("후보", 11_500, 12_000, 1))));
    }

    @Test
    void preservesEmptyFinalBoundaryAndDefaultsOptionalFlagsAndSpeaker() {
        DeepgramMessage message = parser.parse("""
                {"type":"Results","channel_index":[0],"start":0,"duration":0.125,"is_final":true,
                 "channel":{"alternatives":[{"transcript":"","words":[
                   {"word":"boundary","start":0,"end":0.125}]}]}}
                """, 7);

        assertThat(message).isEqualTo(new DeepgramMessage.Results(
                List.of(0), 7, 132, "", true, false, false,
                List.of(new DeepgramMessage.Word("boundary", 7, 132, null))));
    }

    @Test
    void parsesMetadataRedactsErrorsAndBoundsUnknownControls() {
        assertThat(parser.parse("{\"type\":\"Metadata\",\"request_id\":\"req-123\",\"extra\":\"ignored\"}", 0))
                .isEqualTo(new DeepgramMessage.Metadata("req-123"));
        assertThat(parser.parse("""
                {"type":"Error","err_code":"SECRET_CODE","description":"customer transcript",
                 "message":"credential abc"}
                """, 0)).isEqualTo(new DeepgramMessage.Error(DeepgramMessageParser.SANITIZED_ERROR_CODE));
        assertThat(parser.parse("{\"type\":\"KeepAlive\",\"payload\":\"ignored\"}", 0))
                .isEqualTo(new DeepgramMessage.Unknown("KeepAlive"));
        assertThat(parser.parse("{\"type\":\"Finalize\"}", 0))
                .isEqualTo(new DeepgramMessage.Unknown("Finalize"));
        assertThat(parser.parse("{\"type\":\"CloseStream\"}", 0))
                .isEqualTo(new DeepgramMessage.Unknown("CloseStream"));
    }

    @Test
    void rejectsMalformedFlagsTimestampsOverflowAndControlTypesWithoutLeakingPayload() {
        assertInvalid("{\"type\":\"Results\",\"channel_index\":[0],\"start\":0,\"duration\":1,"
                + "\"is_final\":\"true\",\"channel\":{\"alternatives\":[{\"transcript\":\"x\"}]}}", 0);
        assertInvalid("{\"type\":\"Results\",\"channel_index\":[0],\"start\":-1,\"duration\":1,"
                + "\"is_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"x\"}]}}", 0);
        assertInvalid("{\"type\":\"Results\",\"channel_index\":[0],\"start\":1e100,\"duration\":1,"
                + "\"is_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"x\"}]}}", 0);
        assertInvalid("{\"type\":\"Results\",\"channel_index\":[0],\"start\":0.001,\"duration\":0,"
                + "\"is_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"x\"}]}}",
                Long.MAX_VALUE);
        assertInvalid("{\"type\":\"Keep\\u0000Alive\"}", 0);
        assertInvalid("{\"type\":\"Finalize\",\"type\":\"Error\"}", 0);
        assertInvalid("{\"type\":\"Results\",\"channel_index\":[0],\"start\":0,\"duration\":1,"
                + "\"is_final\":false,\"is_final\":true,"
                + "\"channel\":{\"alternatives\":[{\"transcript\":\"x\"}]}}", 0);
        assertInvalid("{\"type\":\"Metadata\",\"request_id\":\"secret", 0);
        assertInvalid("{\"type\":\"Finalize\"} {\"description\":\"secret\"}", 0);
        assertInvalid("{\"type\":\"Finalize\"}", -1);
    }

    @Test
    void rejectsNonFiniteJacksonNumbersAndOversizedInputAndFields() {
        assertInvalid("{\"type\":\"Results\",\"channel_index\":[0],\"start\":NaN,\"duration\":1,"
                + "\"is_final\":true,\"channel\":{\"alternatives\":[{\"transcript\":\"x\"}]}}", 0);
        assertInvalid("{\"type\":\"" + "x".repeat(65) + "\"}", 0);
        assertInvalid("{\"type\":\"Unknown\",\"padding\":\"" + "x".repeat(1_048_576) + "\"}", 0);
    }

    private void assertInvalid(String json, long offset) {
        assertThatThrownBy(() -> parser.parse(json, offset))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Deepgram message")
                .hasNoCause();
    }
}

package dev.aimeeting.api.provider.deepgram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeepgramOptionsTest {
    @Test
    void buildsFixedQueryWithRepeatedRfc3986EncodedKeyterms() {
        DeepgramOptions options = new DeepgramOptions(true, List.of("LOT & WIP", "한글/용어?x=1"));

        assertThat(options.queryString()).isEqualTo(
                "model=nova-3&language=ko&interim_results=true&diarize_model=latest"
                        + "&keyterm=LOT%20%26%20WIP&keyterm=%ED%95%9C%EA%B8%80%2F%EC%9A%A9%EC%96%B4%3Fx%3D1");
        assertThat(options.queryString()).doesNotContain("diarize=true", "token", "auth", "wss://");
    }

    @Test
    void omitsDiarizationParameterWhenDisabledAndDefensivelyCopiesTerms() {
        List<String> terms = new ArrayList<>(List.of("MES"));
        DeepgramOptions options = new DeepgramOptions(false, terms);
        terms.add("BOM");

        assertThat(options.keyterms()).containsExactly("MES");
        assertThat(options.queryString()).isEqualTo(
                "model=nova-3&language=ko&interim_results=true&keyterm=MES");
        assertThatThrownBy(() -> options.keyterms().add("WIP"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void addsRawPcmParametersOnlyForTheExplicitPcmFormat() {
        DeepgramOptions containerized = new DeepgramOptions(false, List.of(),
                DeepgramAudioFormat.containerized());
        DeepgramOptions rawPcm = new DeepgramOptions(false, List.of(),
                DeepgramAudioFormat.pcm16kMono());

        assertThat(containerized.queryString()).doesNotContain("encoding=", "sample_rate=", "channels=");
        assertThat(rawPcm.queryString()).endsWith(
                "&encoding=linear16&sample_rate=16000&channels=1");
        assertThatThrownBy(() -> new DeepgramOptions(false, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("audioFormat");
    }

    @Test
    void rejectsInvalidDistinctCountLengthAndConservativeByteBudget() {
        assertThatThrownBy(() -> new DeepgramOptions(true, List.of(" ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeepgramOptions(true, List.of("LOT\nWIP")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeepgramOptions(true, List.of("LOT", "LOT")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeepgramOptions(true, List.of("x".repeat(201))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeepgramOptions(true, Collections.nCopies(101, "x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DeepgramOptions(true, List.of("한".repeat(167))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

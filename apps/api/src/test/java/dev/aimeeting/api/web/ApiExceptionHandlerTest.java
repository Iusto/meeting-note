package dev.aimeeting.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class ApiExceptionHandlerTest {
    @Test void preservesKnownAsyncFailureAndRequestId() {
        var request = new MockHttpServletRequest();
        request.setAttribute("requestId", "test-request");
        var failure = new ApiException(HttpStatus.BAD_GATEWAY, "OPENAI_MODEL_UNAVAILABLE", "모델 접근 권한을 확인하세요.");
        var response = new ApiExceptionHandler().unexpected(new CompletionException(failure), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().code()).isEqualTo(failure.code());
        assertThat(response.getBody().message()).isEqualTo(failure.getMessage());
        assertThat(response.getBody().requestId()).isEqualTo("test-request");
    }
}

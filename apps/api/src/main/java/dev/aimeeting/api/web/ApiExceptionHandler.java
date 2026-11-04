package dev.aimeeting.api.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.ServletRequestBindingException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException exception, HttpServletRequest request) {
        log.warn("API request failed: code={}, requestId={}", exception.code(), request.getAttribute("requestId"));
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "Request validation failed", request));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class})
    ResponseEntity<ApiError> malformed(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "Request could not be parsed", request));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> status(ResponseStatusException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(error("UNAUTHORIZED", exception.getReason() == null ? "Request rejected" : exception.getReason(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        Throwable cause = exception;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ApiException known) return api(known, request);
        log.error("API request failed: type={}, requestId={}", cause.getClass().getSimpleName(), request.getAttribute("requestId"));
        return ResponseEntity.internalServerError()
                .body(error("INTERNAL_ERROR", "The request could not be completed", request));
    }

    private ApiError error(String code, String message, HttpServletRequest request) {
        Object requestId = request.getAttribute("requestId");
        return new ApiError(code, message, requestId == null ? UUID.randomUUID().toString() : requestId.toString());
    }
}

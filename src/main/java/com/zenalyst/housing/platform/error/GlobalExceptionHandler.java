package com.zenalyst.housing.platform.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as an RFC 9457 {@code application/problem+json} document.
 *
 * <p>One rule governs this class: <em>we describe what the caller got wrong, and we never
 * describe our own internals.</em> Validation failures name the offending field, because the
 * applicant can act on that. Unhandled exceptions return an opaque body and a correlation id,
 * because a stack trace on a public endpoint is an information leak — and this API is public
 * by design.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ValidationFailedException.class)
    public ResponseEntity<ProblemDetail> handleValidationFailed(
            ValidationFailedException ex, HttpServletRequest request) {

        ProblemDetail body = base(ProblemType.VALIDATION_FAILED, ex.getMessage(), request.getRequestURI());
        body.setProperty("violations", ex.violations().stream()
                .sorted(java.util.Comparator.comparing(v -> v.field()))
                .toList());
        return ResponseEntity.status(ProblemType.VALIDATION_FAILED.status()).body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail body = base(ex.type(), ex.getMessage(), request.getRequestURI());
        ex.properties().forEach(body::setProperty);
        return ResponseEntity.status(ex.type().status()).body(body);
    }

    /**
     * Authorisation failures, mapped explicitly.
     *
     * <p>These must be declared before the catch-all below, and the reason is worth recording: a
     * blanket {@code @ExceptionHandler(Exception.class)} swallows Spring Security's
     * {@link AccessDeniedException} and returns 500. The endpoint is secured, the caller is
     * correctly refused, and the response says the server broke — so every authorisation failure
     * looks like a bug and every real bug looks like an authorisation failure.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {

        ProblemDetail body = base(ProblemType.FORBIDDEN,
                "Your credentials do not permit this. If this is your own application, use a token "
                        + "issued for that application number.",
                request.getRequestURI());
        return ResponseEntity.status(ProblemType.FORBIDDEN.status()).body(body);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleUnauthenticated(
            AuthenticationException ex, HttpServletRequest request) {

        ProblemDetail body = base(ProblemType.UNAUTHENTICATED,
                "This endpoint requires a bearer token.", request.getRequestURI());
        return ResponseEntity.status(ProblemType.UNAUTHENTICATED.status()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = java.util.UUID.randomUUID().toString();
        log.error("Unhandled exception [correlationId={}] on {} {}",
                correlationId, request.getMethod(), request.getRequestURI(), ex);

        ProblemDetail body = base(
                ProblemType.INTERNAL_ERROR,
                "The request could not be completed. Quote the correlation id when reporting this.",
                request.getRequestURI());
        body.setProperty("correlationId", correlationId);
        return ResponseEntity.status(ProblemType.INTERNAL_ERROR.status()).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<Map<String, String>> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .sorted((a, b) -> a.get("field").compareTo(b.get("field")))
                .toList();

        ProblemDetail body = base(ProblemType.VALIDATION_FAILED,
                "%d field(s) failed validation".formatted(violations.size()),
                path(request));
        body.setProperty("violations", violations);
        return ResponseEntity.status(ProblemType.VALIDATION_FAILED.status()).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ProblemDetail body = base(ProblemType.MALFORMED_REQUEST,
                "Request body is not valid JSON or does not match the expected shape.",
                path(request));
        return ResponseEntity.status(ProblemType.MALFORMED_REQUEST.status()).body(body);
    }

    private Map<String, String> describe(FieldError error) {
        return Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage());
    }

    private ProblemDetail base(ProblemType type, String detail, String instance) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(type.status(), detail);
        body.setType(URI.create(type.typeUri()));
        body.setTitle(type.title());
        body.setProperty("timestamp", Instant.now().toString());
        if (instance != null) {
            body.setInstance(URI.create(instance));
        }
        return body;
    }

    private String path(WebRequest request) {
        String description = request.getDescription(false);
        return description.startsWith("uri=") ? description.substring(4) : null;
    }
}

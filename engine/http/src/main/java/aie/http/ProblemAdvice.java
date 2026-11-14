package aie.http;

import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure inside a controller or Spring MVC into a problem. A failure on the engine's side is logged here,
 * while the log context still holds the Request-Id, and the caller learns nothing about it but that ID.
 */
@RestControllerAdvice
class ProblemAdvice extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ProblemAdvice.class);

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception failure, @Nullable Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            return internalError(failure);
        }
        // On a 404 Spring's detail only talks about static resources, which mean nothing to a caller.
        String detail = status.value() == HttpStatus.NOT_FOUND.value() ? null : detail(failure, body);
        return Problems.response(status.value(), detail, headers);
    }

    /** Spring's own detail here is only "Failed to read request", so this one says which field is wrong. */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException failure, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return Problems.response(status.value(), UnreadableBodies.detail(failure.getCause()), headers);
    }

    /** Spring writes these for the caller, so they say what is wrong with the request and nothing about the engine. */
    private static @Nullable String detail(Exception failure, @Nullable Object body) {
        if (body instanceof ProblemDetail problem) {
            return problem.getDetail();
        }
        return failure instanceof ErrorResponse response ? response.getBody().getDetail() : null;
    }

    /**
     * Once the answer has started, no problem can follow it, so the failure leaves Spring as it came: RequestIdFilter
     * logs it once and cuts the connection (code point 34).
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> failed(Exception failure, HttpServletResponse response) throws Exception {
        if (response.isCommitted()) {
            throw failure;
        }
        return internalError(failure);
    }

    private static ResponseEntity<Object> internalError(Exception failure) {
        LOG.error("The request failed", failure);
        return Problems.response(HttpStatus.INTERNAL_SERVER_ERROR.value(), null, HttpHeaders.EMPTY);
    }
}

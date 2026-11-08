package aie.http;

import aie.http.api.model.Problem;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The error format of blueprint 6.2: RFC 9457 problem details with the stable codes of blueprint 6.10. */
final class Problems {

    /** No page explains each error, so RFC 9457 says to use this type and the status phrase as the title. */
    private static final String TYPE = "about:blank";

    private Problems() {
    }

    static Problem of(int status, @Nullable String detail) {
        return new Problem(TYPE, title(status), status, code(status)).detail(detail);
    }

    static ResponseEntity<Object> response(int status, @Nullable String detail, HttpHeaders headers) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(of(status, detail));
    }

    private static String title(int status) {
        HttpStatus known = HttpStatus.resolve(status);
        return known == null ? "Error" : known.getReasonPhrase();
    }

    /**
     * The codes a request can earn before any business logic runs. Blueprint 6.10 gives 401, 403 and 404 a code of
     * their own; the modules bring the rest.
     */
    private static String code(int status) {
        return switch (status) {
            case 401 -> "unauthorized";
            case 403 -> "forbidden";
            case 404 -> "not_found";
            default -> status < HttpStatus.INTERNAL_SERVER_ERROR.value() ? "invalid_request" : "internal_error";
        };
    }
}

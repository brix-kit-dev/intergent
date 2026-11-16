package aie.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;

/**
 * Whether a request asks for its answer as a stream of events (M2-7 decision 2): its Accept names text/event-stream with
 * a quality above 0, even beside JSON. A wildcard names nothing, and a quality of 0 refuses. The message operation reads
 * it to choose how to answer, and the Idempotency-Key filter to tell a streamed request from the same one asking for
 * JSON (M2-10 decision 1).
 */
final class StreamedRequests {

    private StreamedRequests() {
    }

    static boolean wanted(List<MediaType> accepted) {
        return accepted.stream().anyMatch(type -> type.getType().equals("text") && type.getSubtype().equals("event-stream")
                && type.getQualityValue() > 0);
    }

    /** The same for a request as it came: an Accept that is no list of media types asks for no stream. */
    static boolean wanted(HttpServletRequest request) {
        try {
            return wanted(MediaType.parseMediaTypes(Collections.list(request.getHeaders(HttpHeaders.ACCEPT))));
        } catch (InvalidMediaTypeException e) {
            return false;
        }
    }
}

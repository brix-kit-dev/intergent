package aie.http;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the traceparent header of W3C Trace Context. The standard says to ignore a value it does not accept and carry
 * on, so a caller with a broken tracer still gets an answer. Only hex digits come out, so nothing a caller sends can
 * forge a log line.
 */
final class Traceparent {

    private static final Pattern FORMAT = Pattern.compile(
            "(?<version>[0-9a-f]{2})-(?<trace>[0-9a-f]{32})-(?<parent>[0-9a-f]{16})-[0-9a-f]{2}(?<rest>-.*)?");
    private static final String INVALID_VERSION = "ff";
    private static final String FIRST_VERSION = "00";
    private static final String NO_TRACE = "0".repeat(32);
    private static final String NO_PARENT = "0".repeat(16);

    private Traceparent() {
    }

    /** Returns the trace ID of the header values on a request. More than one value is invalid, as the standard says. */
    static Optional<String> traceId(List<String> values) {
        if (values.size() != 1) {
            return Optional.empty();
        }
        Matcher header = FORMAT.matcher(values.get(0));
        if (!header.matches() || header.group("version").equals(INVALID_VERSION)) {
            return Optional.empty();
        }
        // Later versions may add fields after these four, and the first version has none.
        if (header.group("version").equals(FIRST_VERSION) && header.group("rest") != null) {
            return Optional.empty();
        }
        String trace = header.group("trace");
        return trace.equals(NO_TRACE) || header.group("parent").equals(NO_PARENT) ? Optional.empty() : Optional.of(trace);
    }
}

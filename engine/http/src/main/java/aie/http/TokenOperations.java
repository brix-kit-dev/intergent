package aie.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The operations a session token may call: those the contract opens to the browser in their x-callers (blueprint 6.3,
 * S-10), each of which lets a token reach only its own end user's conversations. Every other operation, and any path the
 * contract does not have, is for server keys only. CallersTest calls every operation of the contract with a token, so
 * this list and the contract cannot part: an operation for servers on it, or one for browsers off it, fails that test.
 */
final class TokenOperations {

    /** The contract's method and path of each, as the generated interfaces map them. */
    private static final List<Operation> OPERATIONS = List.of(
            new Operation(HttpMethod.POST, "/v1/conversations"),
            new Operation(HttpMethod.GET, "/v1/conversations/{conversation_id}"),
            new Operation(HttpMethod.POST, "/v1/conversations/{conversation_id}/messages"),
            new Operation(HttpMethod.GET, "/v1/conversations/{conversation_id}/messages"),
            new Operation(HttpMethod.GET, "/v1/conversations/{conversation_id}/messages/{message_id}"),
            new Operation(HttpMethod.GET, "/v1/conversations/{conversation_id}/messages/{message_id}/events"),
            new Operation(HttpMethod.POST, "/v1/conversations/{conversation_id}/messages/{message_id}/cancel"));

    private TokenOperations() {
    }

    /**
     * Whether the request is one of them. A HEAD is the GET without its body, as Spring MVC answers it. The servlet path
     * is decoded, so an escaped path names the same operation.
     */
    static boolean take(HttpServletRequest request) {
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        HttpMethod operation = HttpMethod.HEAD.equals(method) ? HttpMethod.GET : method;
        PathContainer path = PathContainer.parsePath(request.getServletPath());
        return OPERATIONS.stream().anyMatch(each -> each.method().equals(operation) && each.path().matches(path));
    }

    private record Operation(HttpMethod method, PathPattern path) {

        Operation(HttpMethod method, String path) {
            this(method, PathPatternParser.defaultInstance.parse(path));
        }
    }
}

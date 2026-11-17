package aie.http;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * The operations a session token may call. The contract opens an operation to the browser in its x-callers (blueprint
 * 6.3, S-10), and M2-12 opens those operations here one step at a time, each together with the check that a token
 * reaches only its own end user's conversations. Every other operation, and any path the contract does not have, is for
 * server keys only. CallersTest calls every operation of the contract with a token, so an operation for servers on this
 * list fails it.
 */
final class TokenOperations {

    /** The contract's method and path of each, as the generated interfaces map them. */
    private static final List<Operation> OPERATIONS = List.of(
            new Operation(HttpMethod.POST, "/v1/conversations"),
            new Operation(HttpMethod.GET, "/v1/conversations/{conversation_id}"));

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

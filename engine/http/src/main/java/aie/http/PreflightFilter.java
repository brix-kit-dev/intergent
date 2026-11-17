package aie.http;

import aie.core.tenancy.api.SiteKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.SortedSet;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The CORS preflights of the browser's operations. A preflight carries no credentials and no body, so this runs before
 * the key check, and it answers by the origin, the path and the method asked for alone.
 *
 * <p>{@code POST /v1/anonymous-sessions} is what a page on the host's site calls before it has any token (blueprint
 * 6.5). A preflight cannot name its site key, so every origin a site key could list passes here, and the POST itself is
 * answered only to the origins of its key.
 *
 * <p>The operations a session token takes ({@link TokenOperations}) pass for any origin of that form too (M2 decision 8):
 * they trust the token in the Authorization header, not the page, so a list of origins would protect nothing and only
 * shut out a host's new site. Each path passes for the methods its operations take, with the headers they read. No
 * cookie is ever taken, so the answer allows no credentials. Any other path is for servers, which send no preflight, so
 * one there goes on to the key check and is refused.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class PreflightFilter extends OncePerRequestFilter {

    static final String PATH = "/v1/anonymous-sessions";
    /** The headers the contract lets the anonymous session take. */
    static final String ALLOWED_HEADERS = "Content-Type, Idempotency-Key, Engine-Version, traceparent";
    /** The headers the browser's operations take: the token, and Last-Event-ID to resume a stream. */
    static final String BROWSER_HEADERS = "Authorization, Content-Type, Idempotency-Key, Engine-Version, traceparent, Last-Event-ID";
    /** How long a browser may keep the answer. Browsers cap it, Chrome at two hours. */
    static final String MAX_AGE_SECONDS = "600";
    /** The answer depends on these, so a cache must keep one per value. */
    static final String VARY = "Origin, Access-Control-Request-Method, Access-Control-Request-Headers";

    /**
     * A preflight is an OPTIONS request with an Origin and the method it asks for (Fetch standard, CORS protocol), and
     * this answers those of the anonymous session and of the browser's operations.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("OPTIONS".equals(request.getMethod()) && request.getHeader(HttpHeaders.ORIGIN) != null
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null
                && (PATH.equals(request.getServletPath()) || !TokenOperations.methods(request).isEmpty()));
    }

    /** The Origin goes back exactly as it came, because the browser compares it as text. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String asked = request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD);
        response.setHeader(HttpHeaders.VARY, VARY);
        if (PATH.equals(request.getServletPath())) {
            answer(response, origin, "POST".equals(asked), "POST", ALLOWED_HEADERS,
                    PATH + " takes POST from an origin that a site key may list");
            return;
        }
        SortedSet<String> methods = TokenOperations.methods(request);
        String allowed = String.join(", ", methods);
        answer(response, origin, methods.contains("HEAD".equals(asked) ? "GET" : asked), allowed, BROWSER_HEADERS,
                "This path takes " + allowed + " from an https origin, or from http on localhost or 127.0.0.1");
    }

    /**
     * 204 with what the browser may send, for an origin a page could have and a method the path takes; 403 without any
     * CORS header otherwise, so the browser sends nothing. A HEAD is the GET without its body.
     */
    private static void answer(HttpServletResponse response, String origin, boolean methodTaken, String methods,
            String headers, String refusal) throws IOException {
        if (SiteKeys.origin(origin).isEmpty() || !methodTaken) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, refusal);
            return;
        }
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, methods);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, headers);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, MAX_AGE_SECONDS);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}

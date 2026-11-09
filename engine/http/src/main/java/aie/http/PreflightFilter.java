package aie.http;

import aie.core.tenancy.api.SiteKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The CORS preflight of {@code POST /v1/anonymous-sessions}, the browser operation that a page on the host's site calls
 * before it has any token (blueprint 6.5). A preflight carries no body, so it cannot name its site key: every origin a
 * site key could list passes here, and the POST itself is answered only to the origins of its key. This runs before the
 * key check, because a preflight carries no credentials. No other path gets CORS yet; M2 sets the rules for the rest.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class PreflightFilter extends OncePerRequestFilter {

    static final String PATH = "/v1/anonymous-sessions";
    /** The headers the contract lets the operation take. */
    static final String ALLOWED_HEADERS = "Content-Type, Idempotency-Key, Engine-Version, traceparent";
    /** How long a browser may keep the answer. Browsers cap it, Chrome at two hours. */
    static final String MAX_AGE_SECONDS = "600";
    /** The answer depends on these, so a cache must keep one per value. */
    static final String VARY = "Origin, Access-Control-Request-Method, Access-Control-Request-Headers";

    /** A preflight is an OPTIONS request with an Origin and the method it asks for (Fetch standard, CORS protocol). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("OPTIONS".equals(request.getMethod()) && PATH.equals(request.getServletPath())
                && request.getHeader(HttpHeaders.ORIGIN) != null
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null);
    }

    /** The Origin goes back exactly as it came, because the browser compares it as text. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        response.setHeader(HttpHeaders.VARY, VARY);
        if (SiteKeys.origin(origin).isEmpty() || !"POST".equals(request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    PATH + " takes POST from an origin that a site key may list");
            return;
        }
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "POST");
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, ALLOWED_HEADERS);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, MAX_AGE_SECONDS);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}

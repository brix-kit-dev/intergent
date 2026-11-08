package aie.http;

import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Blueprint 6.5: a host's server calls the public API with its API key, sent as {@code Authorization: Bearer} (RFC
 * 6750). The key leads to its tenant and application through the system path of blueprint 7.6, and the request carries
 * them from here on as {@link #CALLER}. This runs before the version check, because the version a request gets will
 * depend on its application. The key itself is never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class ApiKeyFilter extends OncePerRequestFilter {

    /** The request attribute that holds the caller's {@link Owner} once the key has passed. */
    static final String CALLER = "aie.http.caller";

    private static final String SCHEME = "Bearer";
    /** RFC 6750 3.1: a request without Bearer credentials gets the challenge alone, a bad key gets the error too. */
    private static final String INVALID_KEY = SCHEME + " error=\"invalid_token\"";
    private static final String ON_BEHALF_OF = "On-Behalf-Of";

    private final ApiKeyLookup keys;

    ApiKeyFilter(ApiKeyLookup keys) {
        this.keys = keys;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PublicApi.contains(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> credentials = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (credentials.size() > 1) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Send one Authorization header");
            return;
        }
        String header = credentials.isEmpty() ? "" : credentials.getFirst().strip();
        int space = header.indexOf(' ');
        if (!(space < 0 ? header : header.substring(0, space)).equalsIgnoreCase(SCHEME)) {
            refuse(response, SCHEME, "Send an API key as Authorization: Bearer");
            return;
        }
        Optional<Owner> owner = keys.find(space < 0 ? "" : header.substring(space).strip());
        if (owner.isEmpty()) {
            refuse(response, INVALID_KEY, "The API key is not valid");
            return;
        }
        // Acting for a lower tenant needs the check of 10-6. Until then no tenant is one this key may act for.
        if (request.getHeader(ON_BEHALF_OF) != null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, ON_BEHALF_OF + " is not supported yet");
            return;
        }
        request.setAttribute(CALLER, owner.get());
        chain.doFilter(request, response);
    }

    /** RFC 9110 15.5.2: every 401 carries a challenge that says how to authenticate. */
    private static void refuse(HttpServletResponse response, String challenge, String detail) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, detail);
    }
}

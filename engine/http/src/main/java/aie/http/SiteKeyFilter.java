package aie.http;

import aie.core.tenancy.api.SiteKeyLookup;
import aie.core.tenancy.api.SiteKeyLookup.Checked;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Blueprint 6.5: a browser on the host's site trades a public site key for an anonymous token with {@code POST
 * /v1/anonymous-sessions}, with no API key. The site key is in the body, so this reads the body first and checks the
 * key and the request's Origin through the system path of blueprint 7.6, before any other part of the request runs. It
 * takes the place {@link ApiKeyFilter} has for every other request.
 *
 * <p>An unknown or revoked key gets 401, and a known key from an origin it does not list gets 403, both without CORS
 * headers, so the page cannot read them. An allowed request carries the key's application from here on as
 * {@link ApiKeyFilter#CALLER}, as a server key's request does, so the version check and idempotency work the same. Its
 * answer, whatever it turns out to be, may be read by that origin alone.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class SiteKeyFilter extends OncePerRequestFilter {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** What the page may read of an answer besides the body. */
    private static final String EXPOSED = "Request-Id";

    private final SiteKeyLookup siteKeys;

    SiteKeyFilter(SiteKeyLookup siteKeys) {
        this.siteKeys = siteKeys;
    }

    /** True for the request this filter checks, which no API key check sees. */
    static boolean handles(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && PreflightFilter.PATH.equals(request.getServletPath());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !handles(request);
    }

    /** The Origin goes back exactly as it came, because the browser compares it as text. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        byte[] body = body(request);
        if (body == null) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "The body may be at most 1 MiB");
            return;
        }
        String siteKey = siteKey(body);
        if (siteKey == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "The body must be a JSON object with a site_key string");
            return;
        }
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        switch (siteKeys.check(siteKey, origin)) {
            case Checked.Unknown unknown -> response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "This site key is not valid");
            case Checked.OtherOrigin otherOrigin -> response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "This site key does not list the origin of the request");
            case Checked.Allowed(var owner) -> {
                request.setAttribute(ApiKeyFilter.CALLER, owner);
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
                response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, EXPOSED);
                chain.doFilter(new CachedBodyRequest(request, body), response);
            }
        }
    }

    /** Null when the body is over the limit. A stated length is checked before anything is read. */
    private static byte[] body(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > IdempotencyFilter.MAX_BODY) {
            return null;
        }
        byte[] body = request.getInputStream().readNBytes(IdempotencyFilter.MAX_BODY + 1);
        return body.length > IdempotencyFilter.MAX_BODY ? null : body;
    }

    /** The site_key of a JSON object, or null for anything else. The model reads the whole body again later. */
    private static String siteKey(byte[] body) {
        try {
            JsonNode key = JSON.readTree(body).get("site_key");
            return key != null && key.isString() ? key.stringValue() : null;
        } catch (JacksonException e) {
            return null;
        }
    }
}

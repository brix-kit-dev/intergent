package aie.http;

import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.LowerTenants;
import aie.core.tenancy.api.LowerTenants.Acting;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SiteKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
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
 *
 * <p>With On-Behalf-Of, a platform host's key acts for one of its lower tenants (blueprint 6.5): the request then
 * carries that tenant and its application of the key's environment, as {@link LowerTenants} decides, and keeps the
 * platform's key ID, so that it shows who acted.
 *
 * <p>A browser sends the short-lived session token of blueprint 6.5 the same way. An API key starts with
 * {@code aie_}, so anything else is checked as a session token: by its signature and expiry alone, with no
 * table read (blueprint 7.6). Every operation says in the contract who may call it, and one that does not name the
 * browser is for server keys only (blueprint 6.3, S-10). A valid token passes on the operations {@link TokenOperations}
 * lists, as its own tenant's application, with its end user as {@link #BROWSER}; elsewhere it is forbidden. An invalid
 * or expired one is refused like a bad key. A token is never logged either.
 *
 * <p>On the browser's operations, a page may read the answer to a token and to a request whose credentials failed, so
 * that it learns, for one, that its token ran out: every such answer lets the request's origin read it, when a page
 * could have that origin. A request a server key passes gets no such header, so a key put in a page by mistake fails
 * there at once (S-5, M2-12 decision 3); a server, which sends its key from behind the host, needs none.
 *
 * <p>The one exception is {@code POST /v1/anonymous-sessions}, which a browser calls with a site key instead:
 * {@link SiteKeyFilter} checks it, in the same place in the chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class ApiKeyFilter extends OncePerRequestFilter {

    /**
     * The request attribute that holds the caller's {@link Owner} once the key or token has passed. A token has no ID of
     * its own, so its owner carries the token's end user ID where a key's carries the key ID: that is who acted.
     */
    static final String CALLER = "aie.http.caller";
    /**
     * The request attribute that holds the {@link SessionTokens.Session} of a request made with a session token, and is
     * not there for one made with a key. The browser's end user is the only one whose conversations it reaches.
     */
    static final String BROWSER = "aie.http.browser";

    private static final String SCHEME = "Bearer";
    /** RFC 6750 3.1: a request without Bearer credentials gets the challenge alone, a bad key gets the error too. */
    private static final String INVALID_KEY = SCHEME + " error=\"invalid_token\"";
    /** RFC 6750 3.1: a valid token that may not make this request. */
    private static final String SERVER_KEY_NEEDED = SCHEME + " error=\"insufficient_scope\"";
    /** Every API key starts this way (blueprint 6.2), and a session token, a signed JWT, never does. */
    private static final String KEY_PREFIX = "aie_";
    /** What a page may read of an answer besides its body: the request's ID, and when to come back after a 429. */
    static final String EXPOSED = "Request-Id, Retry-After";
    private static final String ON_BEHALF_OF = "On-Behalf-Of";
    /** A tenant ID; the contract allows On-Behalf-Of 64 characters. */
    private static final Pattern TENANT_ID = Pattern.compile("ten_[0-9A-Za-z]{1,60}");

    private final ApiKeyLookup keys;
    private final LowerTenants lowerTenants;
    private final SessionTokens sessionTokens;

    ApiKeyFilter(ApiKeyLookup keys, LowerTenants lowerTenants, SessionTokens sessionTokens) {
        this.keys = keys;
        this.lowerTenants = lowerTenants;
        this.sessionTokens = sessionTokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PublicApi.contains(request) || SiteKeyFilter.handles(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        List<String> credentials = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        if (credentials.size() > 1) {
            allowPage(request, response);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Send one Authorization header");
            return;
        }
        String header = credentials.isEmpty() ? "" : credentials.getFirst().strip();
        int space = header.indexOf(' ');
        if (!(space < 0 ? header : header.substring(0, space)).equalsIgnoreCase(SCHEME)) {
            allowPage(request, response);
            refuse(response, SCHEME, TokenOperations.take(request)
                    ? "Send an API key or a session token as Authorization: Bearer"
                    : "Send an API key as Authorization: Bearer");
            return;
        }
        String token = space < 0 ? "" : header.substring(space).strip();
        if (!token.startsWith(KEY_PREFIX)) {
            sessionToken(request, response, chain, token);
            return;
        }
        Optional<Owner> owner = keys.find(token);
        if (owner.isEmpty()) {
            allowPage(request, response);
            refuse(response, INVALID_KEY, "The API key is not valid");
            return;
        }
        Owner caller = owner.get();
        List<String> lower = Collections.list(request.getHeaders(ON_BEHALF_OF));
        if (!lower.isEmpty()) {
            if (lower.size() > 1 || !TENANT_ID.matcher(lower.getFirst()).matches()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Send one " + ON_BEHALF_OF + " with a tenant ID");
                return;
            }
            String tenant = lower.getFirst();
            String environment = ApiKeys.parse(token).orElseThrow().environment();
            switch (lowerTenants.actFor(caller, environment, tenant)) {
                case Acting.As(Owner acting) -> caller = acting;
                case Acting.NotALowerTenant notOne -> {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, tenant + " is not a lower tenant of this key's tenant");
                    return;
                }
                case Acting.NoSingleApplication none -> {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN,
                            tenant + " has no single " + environment + " application to act as");
                    return;
                }
            }
        }
        request.setAttribute(CALLER, caller);
        chain.doFilter(request, response);
    }

    /**
     * A credential that is not an API key. A valid session token passes on an operation that takes one, as its tenant's
     * application and its end user. Anywhere else it is forbidden: blueprint 6.10's {@code forbidden} is a caller without
     * the right to an operation. On-Behalf-Of is refused first, because a token names its own tenant, and the contract
     * keeps the header for server keys. Anything else, an expired or forged token or no credential at all, is refused
     * like a bad key.
     */
    private void sessionToken(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String token)
            throws ServletException, IOException {
        allowPage(request, response);
        Optional<SessionTokens.Verified> verified = sessionTokens.verify(token);
        if (verified.isEmpty()) {
            refuse(response, INVALID_KEY, "The API key or session token is not valid");
            return;
        }
        if (request.getHeader(ON_BEHALF_OF) != null) {
            forbid(response, "A session token acts only for its own tenant, so " + ON_BEHALF_OF + " takes a server key");
            return;
        }
        if (!TokenOperations.take(request)) {
            forbid(response, "This operation takes a server key, not a session token");
            return;
        }
        SessionTokens.Session session = verified.get().session();
        request.setAttribute(CALLER, new Owner(session.endUserId(), session.tenantId(), session.applicationId()));
        request.setAttribute(BROWSER, session);
        chain.doFilter(request, response);
    }

    /**
     * Lets the page that sent a request to one of the browser's paths read the answer, whatever it turns out to be, when
     * the origin is one a page could have (M2 decision 8). The headers go out before the request goes on, so a refusal
     * further on, an error and a stream keep them. Without cookies, no credentials are allowed.
     */
    private static void allowPage(HttpServletRequest request, HttpServletResponse response) {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (SiteKeys.origin(origin).isPresent() && !TokenOperations.methods(request).isEmpty()) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, EXPOSED);
            response.setHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        }
    }

    /** RFC 6750 3.1: a valid token that may not make this request gets 403, and the challenge says why. */
    private static void forbid(HttpServletResponse response, String detail) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, SERVER_KEY_NEEDED);
        response.sendError(HttpServletResponse.SC_FORBIDDEN, detail);
    }

    /** RFC 9110 15.5.2: every 401 carries a challenge that says how to authenticate. */
    private static void refuse(HttpServletResponse response, String challenge, String detail) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, detail);
    }
}

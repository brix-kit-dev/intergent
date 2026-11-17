package aie.http;

import aie.core.tenancy.api.ApiKeyLookup;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.LowerTenants;
import aie.core.tenancy.api.LowerTenants.Acting;
import aie.core.tenancy.api.SessionTokens;
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
 * browser is for server keys only (blueprint 6.3, S-10). No operation takes a token yet, so a valid token is forbidden
 * everywhere, and an invalid or expired one is refused like a bad key. A token is never logged either.
 *
 * <p>The one exception is {@code POST /v1/anonymous-sessions}, which a browser calls with a site key instead:
 * {@link SiteKeyFilter} checks it, in the same place in the chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class ApiKeyFilter extends OncePerRequestFilter {

    /** The request attribute that holds the caller's {@link Owner} once the key has passed. */
    static final String CALLER = "aie.http.caller";

    private static final String SCHEME = "Bearer";
    /** RFC 6750 3.1: a request without Bearer credentials gets the challenge alone, a bad key gets the error too. */
    private static final String INVALID_KEY = SCHEME + " error=\"invalid_token\"";
    /** RFC 6750 3.1: a valid token that may not make this request. */
    private static final String SERVER_KEY_NEEDED = SCHEME + " error=\"insufficient_scope\"";
    /** Every API key starts this way (blueprint 6.2), and a session token, a signed JWT, never does. */
    private static final String KEY_PREFIX = "aie_";
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
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Send one Authorization header");
            return;
        }
        String header = credentials.isEmpty() ? "" : credentials.getFirst().strip();
        int space = header.indexOf(' ');
        if (!(space < 0 ? header : header.substring(0, space)).equalsIgnoreCase(SCHEME)) {
            refuse(response, SCHEME, "Send an API key as Authorization: Bearer");
            return;
        }
        String token = space < 0 ? "" : header.substring(space).strip();
        if (!token.startsWith(KEY_PREFIX)) {
            refuseSessionToken(request, response, token);
            return;
        }
        Optional<Owner> owner = keys.find(token);
        if (owner.isEmpty()) {
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
     * A credential that is not an API key. A valid session token is forbidden, since no operation takes one yet (M2-12-2
     * and M2-12-3 add the browser operations), and blueprint 6.10's {@code forbidden} is a caller without the right to
     * an operation. On-Behalf-Of is refused first: a token names its own tenant, and the contract keeps the header for
     * server keys. Anything else, an expired or forged token or no credential at all, is refused like a bad key.
     */
    private void refuseSessionToken(HttpServletRequest request, HttpServletResponse response, String token)
            throws IOException {
        if (sessionTokens.verify(token).isEmpty()) {
            refuse(response, INVALID_KEY, "The API key or session token is not valid");
            return;
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, SERVER_KEY_NEEDED);
        if (request.getHeader(ON_BEHALF_OF) != null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "A session token acts only for its own tenant, so " + ON_BEHALF_OF + " takes a server key");
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "This operation takes a server key, not a session token");
    }

    /** RFC 9110 15.5.2: every 401 carries a challenge that says how to authenticate. */
    private static void refuse(HttpServletResponse response, String challenge, String detail) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, detail);
    }
}

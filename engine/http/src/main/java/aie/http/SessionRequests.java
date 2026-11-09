package aie.http;

import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.http.api.model.CreateSessionRequest;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The checks on a {@code POST /v1/sessions} body, with the limits of the contract's CreateSessionRequest. The scopes
 * and context travel inside the token, which the browser sends in a request header, so they are kept small. A body
 * that fails a check gets 400 before anything is made, so it leaves no end user behind.
 */
final class SessionRequests {

    static final int MAX_EXTERNAL_ID = 255;
    static final int MAX_SCOPES = 20;
    static final int MAX_SCOPE = 64;
    static final int MAX_CONTEXT = 10;
    static final int MAX_CONTEXT_KEY = 40;
    static final int MAX_CONTEXT_VALUE = 200;
    /** Scope names and context keys, as the contract's pattern says. It must match the whole name. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.:-]+");

    /** A body that passed every check, with the default level and the locale in standard form, or null without one. */
    record Checked(String externalId, IdentityLevel identityLevel, List<String> scopes, Map<String, String> context,
            String locale) {
    }

    private SessionRequests() {
    }

    /** The body as the token will carry it. {@code tokens} counts the bytes, so the limit is what the token holds. */
    static Checked check(CreateSessionRequest body, SessionTokens tokens) {
        String externalId = body.getExternalUserId();
        if (externalId == null || externalId.isEmpty() || externalId.codePointCount(0, externalId.length()) > MAX_EXTERNAL_ID) {
            throw refused("external_user_id must have 1 to " + MAX_EXTERNAL_ID + " characters");
        }
        List<String> scopes = body.getScopes() == null ? List.of() : body.getScopes();
        if (scopes.size() > MAX_SCOPES) {
            throw refused("scopes may have at most " + MAX_SCOPES + " entries");
        }
        if (!scopes.stream().allMatch(scope -> isName(scope, MAX_SCOPE))) {
            throw refused("each scope must have 1 to " + MAX_SCOPE + " letters, digits or _ . : -");
        }
        Map<String, String> context = body.getContext() == null ? Map.of() : body.getContext();
        if (context.size() > MAX_CONTEXT) {
            throw refused("context may have at most " + MAX_CONTEXT + " entries");
        }
        if (!context.keySet().stream().allMatch(key -> isName(key, MAX_CONTEXT_KEY))) {
            throw refused("each context key must have 1 to " + MAX_CONTEXT_KEY + " letters, digits or _ . : -");
        }
        if (!context.values().stream().allMatch(SessionRequests::isContextValue)) {
            throw refused("each context value must be a string of at most " + MAX_CONTEXT_VALUE + " characters");
        }
        String locale = body.getLocale() == null ? null : SessionTokens.standardLocale(body.getLocale()).orElseThrow(() ->
                refused("locale must be a BCP 47 language tag of at most " + SessionTokens.MAX_LOCALE + " characters, such as ja-JP"));
        int bytes = tokens.claimsBytes(scopes, context);
        if (bytes > SessionTokens.MAX_CLAIMS_BYTES) {
            throw refused("scopes and context may take at most " + SessionTokens.MAX_CLAIMS_BYTES
                    + " bytes together as JSON, and these take " + bytes);
        }
        IdentityLevel level = body.getIdentityLevel() == null
                ? IdentityLevel.VERIFIED
                : IdentityLevel.fromWireName(body.getIdentityLevel().getValue()).orElseThrow();
        return new Checked(externalId, level, List.copyOf(scopes), Map.copyOf(context), locale);
    }

    private static boolean isName(String name, int maxLength) {
        return name != null && name.length() <= maxLength && NAME.matcher(name).matches();
    }

    /** The contract counts characters, so a value of 200 symbols outside the basic plane passes too. */
    private static boolean isContextValue(String value) {
        return value != null && value.codePointCount(0, value.length()) <= MAX_CONTEXT_VALUE;
    }

    private static ResponseStatusException refused(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}

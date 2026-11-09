package aie.core.tenancy.api;

import java.time.Duration;
import java.time.Instant;
import java.util.IllformedLocaleException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The short-lived end user tokens of blueprint 6.5. The host's server trades its key for one, and the browser then
 * uses it. A token names its tenant, application and end user, how far the host vouches for that user, what the token
 * may do, the identifiers the host bound to the session, and the end user's language when the host gave one. It is
 * signed, so checking it reads no table (blueprint 7.6), and it ends after {@link #LIFETIME}.
 */
public interface SessionTokens {

    /** How long every token lives. It is fixed, so no caller can ask for a long-lived one. */
    Duration LIFETIME = Duration.ofHours(1);

    /** The longest locale, in characters. The contract's Locale has the same limit. */
    int MAX_LOCALE = 35;

    /**
     * The tag in its standard BCP 47 form, such as {@code ja-JP} for {@code ja-jp} or {@code he} for {@code iw}, or
     * empty when it is not a well-formed tag of at most {@link #MAX_LOCALE} characters.
     */
    static Optional<String> standardLocale(String tag) {
        if (tag == null || tag.length() > MAX_LOCALE) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Locale.Builder().setLanguageTag(tag).build().toLanguageTag());
        } catch (IllformedLocaleException e) {
            return Optional.empty();
        }
    }

    /** A signed token for the session. It expires {@link #LIFETIME} from now. */
    Issued issue(Session session);

    /**
     * The session in a token, or empty when the token is malformed, changed, signed with another algorithm or an
     * unknown key, or expired. Every failure looks the same, so a caller learns nothing about why.
     */
    Optional<Verified> verify(String token);

    /** How far the host vouches for the end user. Anonymous tokens come from site keys, not from a server key. */
    enum IdentityLevel {
        ANONYMOUS,
        UNVERIFIED,
        VERIFIED;

        /** The name on the wire and in the contract. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The level with this wire name, or empty. */
        public static Optional<IdentityLevel> fromWireName(String name) {
            for (IdentityLevel level : values()) {
                if (level.wireName().equals(name)) {
                    return Optional.of(level);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What a token says. {@code scopes} names what the token may do. {@code context} holds the business identifiers
     * the host bound to the session, such as an order number. The model cannot change them, but the browser can read
     * them, so they hold no secrets. {@code locale} is the end user's language as the host gave it, in the form
     * {@link SessionTokens#standardLocale} returns, or null when the host gave none.
     *
     * @throws IllegalArgumentException when the locale is not in that standard form
     */
    record Session(
            String tenantId,
            String applicationId,
            String endUserId,
            IdentityLevel identityLevel,
            List<String> scopes,
            Map<String, String> context,
            String locale) {

        public Session {
            scopes = List.copyOf(scopes);
            context = Map.copyOf(context);
            if (locale != null && !standardLocale(locale).equals(Optional.of(locale))) {
                throw new IllegalArgumentException("A session locale must be a BCP 47 tag in standard form, such as ja-JP");
            }
        }
    }

    /** A new token and the moment it stops working. */
    record Issued(String token, Instant expiresAt) {
    }

    /** The session a valid token carries, and the moment it stops working. */
    record Verified(Session session, Instant expiresAt) {
    }
}

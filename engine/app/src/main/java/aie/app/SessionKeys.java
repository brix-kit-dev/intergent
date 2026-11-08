package aie.app;

import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.internal.SignedSessionTokens;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The api's session signing keys, read from its settings.
 *
 * <p>ENGINE_SESSION_KEY signs every new token. ENGINE_SESSION_PREVIOUS_KEY is optional and only checks tokens, so the
 * key can change without cutting off the tokens already out. Each key holds 256 random bits or more, in base64, the way
 * {@code openssl rand -base64 32} writes them.
 *
 * <p>A refusal names the variable and never shows its value, because the message goes to the log.
 */
final class SessionKeys {

    static final String HOW_TO_MAKE_ONE = "Make one with: openssl rand -base64 32";

    private SessionKeys() {
    }

    /** @throws IllegalArgumentException when a key is missing, not base64, too short, or set twice */
    static SessionTokens tokens(RoleSettings settings, Clock clock) {
        byte[] signingKey = decoded(RoleSettings.SESSION_KEY, settings.required(RoleSettings.SESSION_KEY));
        Optional<String> previous = settings.optional(RoleSettings.SESSION_PREVIOUS_KEY);
        if (previous.isEmpty()) {
            return new SignedSessionTokens(clock, signingKey, List.of());
        }
        byte[] previousKey = decoded(RoleSettings.SESSION_PREVIOUS_KEY, previous.get());
        if (Arrays.equals(previousKey, signingKey)) {
            throw new IllegalArgumentException(RoleSettings.variable(RoleSettings.SESSION_PREVIOUS_KEY) + " is the same as "
                    + RoleSettings.variable(RoleSettings.SESSION_KEY));
        }
        return new SignedSessionTokens(clock, signingKey, List.of(previousKey));
    }

    private static byte[] decoded(String name, String value) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(value.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(RoleSettings.variable(name) + " is not base64. " + HOW_TO_MAKE_ONE);
        }
        if (key.length < SignedSessionTokens.MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException(RoleSettings.variable(name) + " needs at least "
                    + SignedSessionTokens.MINIMUM_KEY_BYTES * 8 + " bits, and this one has " + key.length * 8 + ". "
                    + HOW_TO_MAKE_ONE);
        }
        return key;
    }
}

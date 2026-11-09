package aie.core.tenancy.internal;

import aie.core.tenancy.api.SessionTokens;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Session tokens as JWTs signed with HMAC-SHA256 (HS256), and nothing else.
 *
 * <p>One key signs and every key it lists can check, so a key can be rotated. The new key signs, while tokens from
 * the previous key keep working until they expire. The header's {@code kid} names the key, as the start of the key's
 * SHA-256 hash. A token whose header names any other algorithm fails, even one that the library could check.
 */
public final class SignedSessionTokens implements SessionTokens {

    /** HS256 needs a key at least as long as its hash, which is 256 bits. */
    public static final int MINIMUM_KEY_BYTES = 32;

    private static final String TENANT = "ten";
    private static final String APPLICATION = "app";
    private static final String LEVEL = "lvl";
    private static final String SCOPES = "scp";
    private static final String CONTEXT = "ctx";
    private static final String LOCALE = "loc";
    private static final Pattern TENANT_ID = Pattern.compile("ten_[0-9A-Za-z]{1,60}");
    private static final Pattern APPLICATION_ID = Pattern.compile("app_[0-9A-Za-z]{1,60}");
    private static final Pattern END_USER_ID = Pattern.compile("eu_[0-9A-Za-z]{1,60}");

    private final Clock clock;
    private final String signingKeyId;
    private final MACSigner signer;
    private final Map<String, MACVerifier> verifiers = new HashMap<>();

    /**
     * @param signingKey signs every new token
     * @param previousKeys still accepted, so tokens signed before a rotation keep working until they expire
     * @throws IllegalArgumentException when a key is shorter than 256 bits, or one key is given twice
     */
    public SignedSessionTokens(Clock clock, byte[] signingKey, List<byte[]> previousKeys) {
        this.clock = clock;
        this.signingKeyId = keyId(signingKey);
        try {
            this.signer = new MACSigner(checked(signingKey));
            verifiers.put(signingKeyId, new MACVerifier(signingKey.clone()));
            for (byte[] key : previousKeys) {
                if (verifiers.putIfAbsent(keyId(key), new MACVerifier(checked(key))) != null) {
                    throw new IllegalArgumentException("The same session key is given twice");
                }
            }
        } catch (JOSEException e) {
            throw new IllegalArgumentException("A session key was refused: " + e.getMessage(), e);
        }
    }

    @Override
    public Issued issue(Session session) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = now.plus(LIFETIME);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(session.endUserId())
                .claim(TENANT, session.tenantId())
                .claim(APPLICATION, session.applicationId())
                .claim(LEVEL, session.identityLevel().wireName())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiresAt));
        if (!session.scopes().isEmpty()) {
            claims.claim(SCOPES, session.scopes());
        }
        if (!session.context().isEmpty()) {
            claims.claim(CONTEXT, session.context());
        }
        if (session.locale() != null) {
            claims.claim(LOCALE, session.locale());
        }
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(signingKeyId).type(JOSEObjectType.JWT).build();
        SignedJWT token = new SignedJWT(header, claims.build());
        try {
            token.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a session token", e);
        }
        return new Issued(token.serialize(), expiresAt);
    }

    @Override
    public Optional<Verified> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            JWSHeader header = jwt.getHeader();
            MACVerifier verifier = header.getKeyID() == null ? null : verifiers.get(header.getKeyID());
            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm()) || verifier == null || !jwt.verify(verifier)) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expiresAt = claims.getExpirationTime();
            if (expiresAt == null || !clock.instant().isBefore(expiresAt.toInstant())) {
                return Optional.empty();
            }
            return session(claims).map(session -> new Verified(session, expiresAt.toInstant()));
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    /** The session in checked claims, or empty when one is missing or has the wrong shape. */
    private static Optional<Session> session(JWTClaimsSet claims) throws ParseException {
        String tenantId = claims.getStringClaim(TENANT);
        String applicationId = claims.getStringClaim(APPLICATION);
        String endUserId = claims.getSubject();
        Optional<IdentityLevel> level = IdentityLevel.fromWireName(claims.getStringClaim(LEVEL));
        List<String> scopes = claims.getStringListClaim(SCOPES);
        Map<String, Object> context = claims.getJSONObjectClaim(CONTEXT);
        String locale = claims.getStringClaim(LOCALE);
        if (!matches(TENANT_ID, tenantId) || !matches(APPLICATION_ID, applicationId) || !matches(END_USER_ID, endUserId)
                || level.isEmpty() || scopes != null && scopes.contains(null)
                || locale != null && !SessionTokens.standardLocale(locale).equals(Optional.of(locale))) {
            return Optional.empty();
        }
        Map<String, String> strings = new LinkedHashMap<>();
        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                if (!(entry.getValue() instanceof String value)) {
                    return Optional.empty();
                }
                strings.put(entry.getKey(), value);
            }
        }
        return Optional.of(new Session(
                tenantId, applicationId, endUserId, level.get(), scopes == null ? List.of() : scopes, strings, locale));
    }

    private static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    private static byte[] checked(byte[] key) {
        if (key.length < MINIMUM_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "A session key needs at least " + MINIMUM_KEY_BYTES * 8 + " bits, and this one has " + key.length * 8);
        }
        return key.clone();
    }

    /** The first 8 bytes of the key's SHA-256 hash, in hex. It names the key and says nothing about it. */
    static String keyId(byte[] key) {
        try {
            return HexFormat.of().formatHex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(key), 8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every JDK has SHA-256", e);
        }
    }
}

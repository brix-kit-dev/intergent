package aie.core.tenancy.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The API keys of blueprint 6.2 and 6.5, such as {@code aie_live_jp1_...}. The environment and the cell code can be read
 * without the database, so a key can be routed before it is looked up. Only a hash is ever stored.
 */
public final class ApiKeys {

    private static final String PREFIX = "aie";
    private static final char[] SECRET_DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    /** About 238 bits, so a key cannot be guessed. Underscores never appear in it, since they separate the parts. */
    private static final int SECRET_LENGTH = 40;
    private static final Pattern FORMAT = Pattern.compile(
            "aie_(?<environment>live|test)_(?<cell>[a-z0-9]{1,8})_(?<secret>[0-9A-Za-z]{" + SECRET_LENGTH + "})");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {
    }

    /** What a key says about itself. */
    public record Parsed(String environment, String cellCode) {
    }

    /** A new key for an application of this environment in this cell. */
    public static String issue(String environment, String cellCode) {
        String issued = PREFIX + "_" + environment + "_" + cellCode + "_" + secret();
        if (parse(issued).isEmpty()) {
            throw new IllegalArgumentException("No key can be made for environment " + environment + " in cell " + cellCode);
        }
        return issued;
    }

    /** The random part of a key, which site keys share. */
    static String secret() {
        StringBuilder secret = new StringBuilder(SECRET_LENGTH);
        for (int i = 0; i < SECRET_LENGTH; i++) {
            secret.append(SECRET_DIGITS[RANDOM.nextInt(SECRET_DIGITS.length)]);
        }
        return secret.toString();
    }

    /** Reads a key without the database. Anything else, a malformed key included, gives nothing. */
    public static Optional<Parsed> parse(String key) {
        Matcher matcher = FORMAT.matcher(key);
        return matcher.matches() ? Optional.of(new Parsed(matcher.group("environment"), matcher.group("cell"))) : Optional.empty();
    }

    /**
     * The value the database keeps. A plain SHA-256 is enough for a secret this long: a slow hash defends weak
     * passwords, and nobody chooses these.
     */
    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }
}

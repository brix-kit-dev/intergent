package aie.core;

import java.security.SecureRandom;

/**
 * The IDs of blueprint 6.2: a type prefix, then the time, then randomness. The time comes first so that later IDs sort
 * after earlier ones, and the randomness keeps one ID from giving away its neighbours.
 */
public final class Ids {

    /** Crockford's base 32. It sorts in ASCII order and leaves out I, L, O and U, which are easy to misread. */
    private static final char[] DIGITS = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int TIME_DIGITS = 10;
    private static final int RANDOM_DIGITS = 16;
    private static final int BITS_PER_DIGIT = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ids() {
    }

    public static String next(String prefix) {
        return next(prefix, System.currentTimeMillis());
    }

    static String next(String prefix, long millis) {
        char[] digits = new char[TIME_DIGITS + RANDOM_DIGITS];
        long time = millis;
        for (int i = TIME_DIGITS - 1; i >= 0; i--) {
            digits[i] = DIGITS[(int) (time & (DIGITS.length - 1))];
            time >>>= BITS_PER_DIGIT;
        }
        for (int i = TIME_DIGITS; i < digits.length; i++) {
            digits[i] = DIGITS[RANDOM.nextInt(DIGITS.length)];
        }
        return prefix + "_" + new String(digits);
    }
}

package aie.adapters.redactionrules;

import aie.ports.RedactionPort;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link RedactionPort} by rules inside the engine (blueprint 4.1, M3 decision 5): no text leaves the process. It masks
 * the personal data a customer most often types (M3-4-2): email addresses, payment card numbers and telephone numbers,
 * with digits half-width or full-width. Each kind has its placeholder, {@code [email]}, {@code [card]} and
 * {@code [phone]}.
 *
 * <p>The rules mask what looks like such data and leave the rest of the text as it was:
 * <ul>
 *   <li>an email address: a local part, {@code @} or {@code ＠}, and a domain with a dot;
 *   <li>a card number: 13 to 19 digits, single spaces or hyphens between them allowed, that pass the Luhn check, so
 *       an order number of the same length rarely does;
 *   <li>a telephone number: with a country code after {@code +}, 8 to 15 digits; a national number from {@code 0}, 10
 *       or 11 digits, the area code in brackets or not; or a Chinese mobile number, 11 digits from {@code 13} to
 *       {@code 19}. Groups may be split by a space or a hyphen of any width, and by the long vowel mark that Japanese
 *       keyboards often give for one.
 * </ul>
 * A number inside a longer run of digits is not taken for one of these. Emails go first, then cards, then phones, so the
 * digits of an address or a card are not read as a phone. Rules always miss some personal data, so masking never lowers
 * a data class (blueprint 7.3).
 */
public final class RuleRedaction implements RedactionPort {

    private static final String DIGIT = "[0-9０-９]";
    /** Between two groups of a number: a space, a hyphen of any width, or the long vowel mark typed for one. */
    private static final String SEPARATOR = "[\\x20\\u3000\\-‐－ー]";

    private static final Pattern EMAIL = Pattern.compile(
            "(?<![A-Za-z0-9._%+\\-])[A-Za-z0-9._%+\\-]+[@＠][A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}(?![A-Za-z0-9\\-])");
    private static final Pattern CARD = Pattern.compile(
            "(?<![0-9０-９+＋])" + DIGIT + "(?:" + SEPARATOR + "?" + DIGIT + "){12,18}(?![0-9０-９])");
    private static final Pattern INTERNATIONAL = Pattern.compile("(?<![0-9０-９A-Za-z])[+＋]" + DIGIT + "{1,3}(?:" + SEPARATOR
            + "?[(（]?" + DIGIT + "{1,4}[)）]?){2,5}(?![0-9０-９])");
    private static final Pattern NATIONAL = Pattern.compile("(?<![0-9０-９+＋])[(（]?[0０]" + DIGIT + "{1,4}[)）]?" + SEPARATOR
            + "?" + DIGIT + "{1,4}" + SEPARATOR + "?" + DIGIT + "{3,4}(?![0-9０-９])");
    private static final Pattern CHINESE_MOBILE = Pattern.compile("(?<![0-9０-９+＋])[1１][3-9３-９]" + DIGIT + SEPARATOR + "?"
            + DIGIT + "{4}" + SEPARATOR + "?" + DIGIT + "{4}(?![0-9０-９])");

    /** The rules in the order they apply, each with the test a match must also pass. */
    private static final List<Rule> RULES = List.of(
            new Rule(Kind.EMAIL, EMAIL, match -> true),
            new Rule(Kind.CARD, CARD, RuleRedaction::passesLuhn),
            new Rule(Kind.PHONE, INTERNATIONAL, match -> between(digits(match).length(), 8, 15)),
            new Rule(Kind.PHONE, CHINESE_MOBILE, match -> true),
            new Rule(Kind.PHONE, NATIONAL, match -> between(digits(match).length(), 10, 11)));

    @Override
    public Redacted redact(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Masking needs a text");
        }
        Set<Kind> found = EnumSet.noneOf(Kind.class);
        String masked = text;
        for (Rule rule : RULES) {
            masked = rule.apply(masked, found);
        }
        return new Redacted(masked, Set.copyOf(found));
    }

    /** The placeholder of a kind, such as [phone]. */
    static String placeholder(Kind kind) {
        return "[" + kind.name().toLowerCase(Locale.ROOT) + "]";
    }

    /** The digits of a match, full-width ones as their plain values. */
    private static String digits(String match) {
        StringBuilder digits = new StringBuilder();
        match.codePoints().filter(Character::isDigit).forEach(point -> digits.append(Character.digit(point, 10)));
        return digits.toString();
    }

    private static boolean between(int value, int least, int most) {
        return value >= least && value <= most;
    }

    /** Whether the digits pass the Luhn check that every payment card number passes. */
    private static boolean passesLuhn(String match) {
        String digits = digits(match);
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }

    /**
     * One rule: what it finds, and what a match must also be.
     *
     * @param kind the kind of personal data it masks
     * @param pattern what looks like it
     * @param holds whether a match is one, such as a card number that passes the Luhn check
     */
    private record Rule(Kind kind, Pattern pattern, Predicate<String> holds) {

        /** The text with each match that holds masked, noting the kind when there was one. */
        String apply(String text, Set<Kind> found) {
            Matcher matcher = pattern.matcher(text);
            return matcher.replaceAll(match -> {
                if (!holds.test(match.group())) {
                    return Matcher.quoteReplacement(match.group());
                }
                found.add(kind);
                return Matcher.quoteReplacement(placeholder(kind));
            });
        }
    }
}

package aie.core.trust.internal;

import aie.core.trust.api.CheckMode;
import aie.core.trust.api.Checks;
import aie.ports.ModerationPort;
import aie.ports.RedactionPort;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The output check of one answer that is held while it is checked (blueprint 9.2, M3-4-3): by the sentence, or whole.
 * Each unit it checks, a sentence or the whole answer, is stopped when it repeats {@link #LEAK} characters of the
 * instructions in a row (M3-4 decision 3), or when the moderation blocks it; otherwise it goes out masked. Once it has
 * stopped the answer, it lets nothing more out.
 *
 * <p>A sentence ends after {@code 。．！？!?}, a line break, or a full stop followed by a space; the spaces after its
 * end go out with it. A full stop at the very end of what has come so far may be part of a number, so it waits for the
 * next piece.
 */
final class HeldOutput implements Checks.Output {

    /** How many characters of the instructions in a row give them away. */
    static final int LEAK = 40;
    /** The rule that stops an answer that gives the instructions away. */
    static final String PROMPT_LEAK = "prompt_leak";
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private final CheckMode mode;
    /** Every stretch of {@link #LEAK} characters of the instructions, their white space folded as {@link #folded} does. */
    private final Set<String> instructions;
    private final ModerationPort moderation;
    private final RedactionPort redaction;
    private final StringBuilder held = new StringBuilder();
    private Checks.Stop stopped;

    HeldOutput(CheckMode mode, Set<String> instructions, ModerationPort moderation, RedactionPort redaction) {
        if (mode == CheckMode.INPUT) {
            throw new IllegalArgumentException("An answer whose route checks its input alone is not held");
        }
        this.mode = mode;
        this.instructions = instructions;
        this.moderation = moderation;
        this.redaction = redaction;
    }

    /** Every stretch of {@link #LEAK} characters of a text, its white space folded, so that a lookup finds a repeat. */
    static Set<String> stretches(String text) {
        String folded = folded(text);
        Set<String> stretches = new HashSet<>();
        for (int i = 0; i + LEAK <= folded.length(); i++) {
            stretches.add(folded.substring(i, i + LEAK));
        }
        return Set.copyOf(stretches);
    }

    /** White space of any kind and length as one space, so that a line break where the prompt had a space changes nothing. */
    private static String folded(String text) {
        return WHITESPACE.matcher(text).replaceAll(" ");
    }

    @Override
    public Checks.Verdict piece(String piece) {
        if (stopped != null) {
            return stopped;
        }
        held.append(piece);
        if (mode == CheckMode.FULL) {
            return new Checks.Release("");
        }
        StringBuilder released = new StringBuilder();
        for (int end = sentenceEnd(held); end > 0; end = sentenceEnd(held)) {
            String sentence = held.substring(0, end);
            held.delete(0, end);
            Checks.Verdict verdict = checked(sentence);
            if (verdict instanceof Checks.Stop stop) {
                return stop;
            }
            released.append(((Checks.Release) verdict).text());
        }
        return new Checks.Release(released.toString());
    }

    /** The rest goes out now: the last sentence, or the whole answer, once it has been checked. */
    @Override
    public Checks.Verdict end() {
        if (stopped != null) {
            return stopped;
        }
        String rest = held.toString();
        held.setLength(0);
        return rest.isEmpty() ? new Checks.Release("") : checked(rest);
    }

    /** One unit, checked: stopped for a leak or by the moderation, or released masked. */
    private Checks.Verdict checked(String unit) {
        if (leaks(unit)) {
            stopped = new Checks.Stop(PROMPT_LEAK);
            return stopped;
        }
        if (moderation.moderate(new ModerationPort.Content(ModerationPort.Direction.OUTPUT, unit))
                instanceof ModerationPort.Blocked blocked) {
            stopped = new Checks.Stop(blocked.rule());
            return stopped;
        }
        return new Checks.Release(Objects.requireNonNull(redaction.redact(unit).text(), "The redaction gave no text"));
    }

    /** Whether the unit repeats {@link #LEAK} characters of the instructions in a row. */
    private boolean leaks(String unit) {
        String folded = folded(unit);
        for (int i = 0; i + LEAK <= folded.length(); i++) {
            if (instructions.contains(folded.substring(i, i + LEAK))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the first sentence of the text ends, after the spaces that follow it, or 0 when no sentence has ended yet. A
     * full stop ends one only when a space follows it.
     */
    static int sentenceEnd(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ends = switch (c) {
                case '。', '．', '！', '？', '!', '?', '\n' -> true;
                case '.' -> i + 1 < text.length() && Character.isWhitespace(text.charAt(i + 1));
                default -> false;
            };
            if (ends) {
                int end = i + 1;
                while (end < text.length() && Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                return end;
            }
        }
        return 0;
    }
}

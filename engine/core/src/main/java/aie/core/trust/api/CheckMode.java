package aie.core.trust.api;

import java.util.Locale;
import java.util.Optional;

/**
 * How a task's answer is checked while it streams (blueprint 9.2). Every task's route names one, and a region pack
 * with a task that names none does not load (A-15, R-4). The checks themselves come with the trust layer (M3-4).
 */
public enum CheckMode {
    /** Holds each sentence until it is checked; a later sentence that fails ends the answer with message.blocked. */
    SENTENCE,
    /** Holds the whole answer until it is checked, for high-risk content such as a refund decision. */
    FULL,
    /** Checks the input alone, for a call with no text out, such as an embedding. */
    INPUT;

    /** The name in a region pack. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The mode with this name, or empty. */
    public static Optional<CheckMode> fromWireName(String name) {
        for (CheckMode mode : values()) {
            if (mode.wireName().equals(name)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}

package aie.ports;

import java.util.Set;

/**
 * Masking of personal data (blueprint 4.1): finds personal data in a text and puts a placeholder in its place, such as
 * {@code [phone]}, so that a model is not shown it. It names no vendor: the first adapter applies rules inside the
 * engine (M3 decision 5), and a dedicated service can take its place later without a change anywhere else. An adapter
 * that calls a service keeps its own time limit on the call (A-9). Rules always miss some personal data, so masking is
 * a layer of protection on top of the data class, never a reason to lower it (blueprint 7.3). Only the trust layer calls
 * this port, and the gateway calls the trust layer.
 */
public interface RedactionPort {

    /**
     * Masks the personal data of one text.
     *
     * @throws IllegalArgumentException for a text that is missing
     */
    Redacted redact(String text);

    /**
     * A text with its personal data masked.
     *
     * @param text the text with each piece of personal data it found replaced by the placeholder of its kind
     * @param found the kinds of personal data it found, none when the text is as it was
     */
    record Redacted(String text, Set<Kind> found) {
    }

    /** What kind of personal data a piece is. */
    enum Kind {
        /** A telephone number, masked as {@code [phone]}. */
        PHONE,
        /** An email address, masked as {@code [email]}. */
        EMAIL,
        /** A payment card number, masked as {@code [card]}. */
        CARD
    }
}

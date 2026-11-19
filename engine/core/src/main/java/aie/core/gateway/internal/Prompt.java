package aie.core.gateway.internal;

import java.util.regex.Pattern;

/**
 * One version of a task's prompt for one model family (blueprint 8.7), named as the region pack names it: task, family
 * and version, such as customer_chat/fake/v1. A published version never changes; a new text is a new version (M3-2).
 *
 * @param id the version's name
 * @param text the instructions the model gets
 */
public record Prompt(String id, String text) {

    /** The task, the family and the version, such as customer_chat/fake/v1. */
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*/[a-z][a-z0-9-]*/v[1-9][0-9]{0,3}");

    /** @throws IllegalArgumentException for a name that is not task/family/vN, or no text */
    public Prompt {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A prompt is named task/family/vN, such as customer_chat/fake/v1, not " + id);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The prompt " + id + " has no text");
        }
    }
}

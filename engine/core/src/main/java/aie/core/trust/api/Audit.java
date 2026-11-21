package aie.core.trust.api;

import java.util.Locale;

/**
 * The audit of blueprint 9.4: what the gateway did on the way to a model and back, one entry at a time, added and never
 * changed (M3-5-1). The gateway adds the entries, so no module goes around them (blueprint 9.1). An entry names who asked
 * by the engine's own IDs alone, the end user by {@code end_user_id} and never by the host's ID (7.5), and it keeps no text
 * of what anyone said, at any class: the text stays in the conversation, which the entry names (M3-5 decision 3). A
 * sensitive entry keeps the HMAC of what it was about instead, by its tenant's own key, with the key's ID (M3-5-3).
 */
public interface Audit {

    /**
     * Adds an entry, in a short transaction of its own, outside any other. When its class is sensitive, the entry keeps
     * the HMAC-SHA256 of the content's question or answer by the tenant's audit key, made at the tenant's first such
     * entry; the content itself is never kept or shown. At any other class the content goes unused.
     *
     * @throws RuntimeException when the entry cannot be added, such as {@link aie.ports.DatabaseException} for a database
     *     that is out of reach or an entry whose fields do not fit its event, or a sensitive one without the content its
     *     event needs; nothing is added then
     */
    void add(Entry entry, Content content);

    /** Adds an entry about no content, such as a blocked input. */
    default void add(Entry entry) {
        add(entry, Content.NONE);
    }

    /**
     * One entry. What it carries depends on its event:
     * <ul>
     *   <li>{@link Event#INPUT_BLOCKED}: the rule; no message, class, model or prompt, since nothing was stored or sent.
     *   <li>{@link Event#MODEL_CALLED}, {@link Event#COMPLETED}, {@link Event#CANCELLED} and {@link Event#FAILED}: the
     *       message the answer is written to, the call's class, the model and the prompt; no rule.
     *   <li>{@link Event#OUTPUT_BLOCKED}: all of those and the rule.
     * </ul>
     *
     * @param requestId the request the entry belongs to, as the log lines of that request carry it, or null for a call
     *     that no request made
     * @param tenantId the tenant that asked
     * @param applicationId the application that asked
     * @param endUserId the end user the call is for, the engine's own ID
     * @param conversationId the conversation the call is for
     * @param messageId the message the answer is written to
     * @param task the task, such as {@code customer_chat}
     * @param event what happened
     * @param dataClass the class of the call
     * @param model the model's ID in the registry
     * @param prompt the prompt's version, such as {@code customer_chat/fake/v1}
     * @param rule the rule that blocked the text, a stable name such as {@code instruction_override}
     */
    record Entry(String requestId, String tenantId, String applicationId, String endUserId, String conversationId,
            String messageId, String task, Event event, DataClass dataClass, String model, String prompt, String rule) {
    }

    /**
     * What an entry is about, for the HMAC of a sensitive entry alone (M3-5-3). The audit never keeps it, and it never
     * shows itself, not even in this record's own text, so it cannot reach a log line by mistake.
     *
     * @param question what the end user said, as it is stored, for the entry before a call
     * @param answer what the caller got of the answer, as the conversation stores it, for the entry at a call's end
     */
    record Content(String question, String answer) {

        /** No content, for an entry that is about none. */
        public static final Content NONE = new Content(null, null);

        @Override
        public String toString() {
            return "Content[question " + length(question) + ", answer " + length(answer) + "]";
        }

        private static String length(String text) {
            return text == null ? "none" : text.length() + " characters";
        }
    }

    /** What an entry tells. */
    enum Event {
        /** The input check blocked what the end user said, so nothing of it went to a model. */
        INPUT_BLOCKED,
        /** The gateway is about to call the model: added first, or the model is not called. */
        MODEL_CALLED,
        /** The model completed its answer. */
        COMPLETED,
        /** The caller asked the call to stop, and it stopped. */
        CANCELLED,
        /** The model failed, or the time ran out. */
        FAILED,
        /** The output check stopped the answer. */
        OUTPUT_BLOCKED;

        /** The name the database and the audit's readers use, such as {@code model_called}. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}

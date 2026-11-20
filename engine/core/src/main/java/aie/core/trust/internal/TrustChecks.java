package aie.core.trust.internal;

import aie.core.trust.api.CheckMode;
import aie.core.trust.api.Checks;
import aie.core.trust.api.DataClass;
import aie.ports.ModerationPort;
import aie.ports.RedactionPort;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Checks} through {@link ModerationPort} and {@link RedactionPort} (blueprint 9.1). The input check is the
 * moderation's verdict on the text on its way in (M3-4-1): a text it blocks is blocked by the port's rule. A text that
 * passes and holds personal data calls for the personal class at least (M3-4-2). What a model is shown is masked by the
 * redaction. An answer is held by its route's check mode, checked unit by unit and let out masked (M3-4-3), see
 * {@link HeldOutput}.
 */
public final class TrustChecks implements Checks {

    private final ModerationPort moderation;
    private final RedactionPort redaction;
    /** The stretches of each prompt an answer may not repeat, worked out once, since a region pack has a few prompts. */
    private final Map<String, Set<String>> stretches = new ConcurrentHashMap<>();

    public TrustChecks(ModerationPort moderation, RedactionPort redaction) {
        this.moderation = Objects.requireNonNull(moderation, "The trust layer checks through a moderation port");
        this.redaction = Objects.requireNonNull(redaction, "The trust layer masks personal data through a redaction port");
    }

    /**
     * A blocked verdict that names no rule, or no verdict at all, is the adapter's mistake. It fails the check with an
     * exception, so the text is neither stored nor sent. So does a redaction that gives no answer.
     */
    @Override
    public Input input(String task, String text) {
        if (task == null || text == null) {
            throw new IllegalArgumentException("The input check needs the task and the text");
        }
        return switch (moderation.moderate(new ModerationPort.Content(ModerationPort.Direction.INPUT, text))) {
            case ModerationPort.Allowed allowed ->
                    new Passed(redaction.redact(text).found().isEmpty() ? DataClass.PUBLIC : DataClass.PERSONAL);
            case ModerationPort.Blocked blocked -> new Blocked(blocked.rule());
        };
    }

    @Override
    public String masked(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Only a text can be masked");
        }
        return Objects.requireNonNull(redaction.redact(text).text(), "The redaction gave no text");
    }

    /** With the input check alone, every piece goes out as it came, unchecked and unmasked (blueprint 9.2). */
    @Override
    public Output output(String task, CheckMode mode, String instructions) {
        if (task == null || mode == null || instructions == null) {
            throw new IllegalArgumentException("The output check needs the task, its check mode and the instructions");
        }
        if (mode == CheckMode.INPUT) {
            return new Output() {
                @Override
                public Verdict piece(String piece) {
                    return new Release(piece);
                }

                @Override
                public Verdict end() {
                    return new Release("");
                }
            };
        }
        return new HeldOutput(mode, stretches.computeIfAbsent(instructions, HeldOutput::stretches), moderation, redaction);
    }
}

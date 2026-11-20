package aie.core.trust.internal;

import aie.core.trust.api.Checks;
import aie.core.trust.api.DataClass;
import aie.ports.ModerationPort;
import aie.ports.RedactionPort;
import java.util.Objects;

/**
 * {@link Checks} through {@link ModerationPort} and {@link RedactionPort} (blueprint 9.1). The input check is the
 * moderation's verdict on the text on its way in (M3-4-1): a text it blocks is blocked by the port's rule. A text that
 * passes and holds personal data calls for the personal class at least (M3-4-2). What a model is shown is masked by the
 * redaction. The output check passes every piece until M3-4-3.
 */
public final class TrustChecks implements Checks {

    private final ModerationPort moderation;
    private final RedactionPort redaction;

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

    @Override
    public String output(String task, String piece) {
        return piece;
    }
}

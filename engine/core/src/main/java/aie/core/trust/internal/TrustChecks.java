package aie.core.trust.internal;

import aie.core.trust.api.Checks;
import aie.ports.ModerationPort;
import java.util.Objects;

/**
 * {@link Checks} through {@link ModerationPort} (blueprint 9.1). The input check is the port's verdict on the text on its
 * way in (M3-4-1): a text it blocks is blocked by the port's rule. The output check passes every piece until M3-4-3.
 */
public final class TrustChecks implements Checks {

    private final ModerationPort moderation;

    public TrustChecks(ModerationPort moderation) {
        this.moderation = Objects.requireNonNull(moderation, "The trust layer checks through a moderation port");
    }

    /**
     * A blocked verdict that names no rule, or no verdict at all, is the adapter's mistake. It fails the check with an
     * exception, so the text is neither stored nor sent.
     */
    @Override
    public Input input(String task, String text) {
        if (task == null || text == null) {
            throw new IllegalArgumentException("The input check needs the task and the text");
        }
        return switch (moderation.moderate(new ModerationPort.Content(ModerationPort.Direction.INPUT, text))) {
            case ModerationPort.Allowed allowed -> new Passed();
            case ModerationPort.Blocked blocked -> new Blocked(blocked.rule());
        };
    }

    @Override
    public String output(String task, String piece) {
        return piece;
    }
}

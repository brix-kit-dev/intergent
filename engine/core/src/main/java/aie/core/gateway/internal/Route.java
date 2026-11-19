package aie.core.gateway.internal;

import aie.core.trust.api.CheckMode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Where a task goes (blueprint 8.4): the models that may answer it, first choice first, the longest one answer may
 * take, and how its answer is checked. A route always names its checks, so a task cannot reach a model unchecked by
 * leaving them out (A-15); the checks themselves come with the trust layer (M3-4). There is no fallback yet, so only
 * the first model answers.
 *
 * @param models the models' IDs in the registry, in order of preference
 * @param timeout the time limit of every call on this route (A-9)
 * @param checks how the answer is checked while it streams (blueprint 9.2)
 */
public record Route(List<String> models, Duration timeout, CheckMode checks) {

    public Route {
        if (models == null || models.isEmpty() || models.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A route needs at least one model, not " + models);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("A route needs a time limit above zero, not " + timeout);
        }
        if (checks == null) {
            throw new IllegalArgumentException("A route needs its checks: sentence, full or input");
        }
        models = List.copyOf(models);
    }
}

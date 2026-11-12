package aie.core.gateway.internal;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Where a task goes (blueprint 8.4): the models that may answer it, first choice first, and the longest one answer may
 * take. There is no fallback yet, so only the first model answers.
 *
 * @param models the models' IDs in the registry, in order of preference
 * @param timeout the time limit of every call on this route (A-9)
 */
public record Route(List<String> models, Duration timeout) {

    public Route {
        if (models == null || models.isEmpty() || models.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A route needs at least one model, not " + models);
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("A route needs a time limit above zero, not " + timeout);
        }
        models = List.copyOf(models);
    }
}

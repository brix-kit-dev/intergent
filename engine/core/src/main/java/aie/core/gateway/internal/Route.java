package aie.core.gateway.internal;

import aie.core.trust.api.CheckMode;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Where a task goes (blueprint 8.4): the models that may answer it, first choice first, the longest one answer may
 * take, how its answer is checked, and each model's prompt. A route always names its checks, so a task cannot reach a
 * model unchecked by leaving them out (A-15); the checks themselves come with the trust layer (M3-4). Every model has
 * the prompt of its family, so a fallback to another family is told what that family needs (blueprint 8.7). There is
 * no fallback yet, so only the first model answers.
 *
 * @param models the models' IDs in the registry, in order of preference
 * @param timeout the time limit of every call on this route (A-9)
 * @param checks how the answer is checked while it streams (blueprint 9.2)
 * @param prompts each model's prompt, by model ID: one for every model of the route and no other (M3-2)
 */
public record Route(List<String> models, Duration timeout, CheckMode checks, Map<String, Prompt> prompts) {

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
        if (prompts == null || !prompts.keySet().equals(new HashSet<>(models)) || prompts.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A route needs a prompt for each of its models " + models + " and no other, not "
                    + (prompts == null ? null : prompts.keySet()));
        }
        models = List.copyOf(models);
        prompts = Map.copyOf(prompts);
    }
}

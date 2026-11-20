package aie.app;

import aie.core.gateway.api.ModelSpec;
import aie.core.gateway.internal.Route;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A region pack as the api role loads it (blueprint 12.2): the models this region may use, each with what its contract
 * allows, each task's route, and the region's policy. {@link RegionPacks} reads it from the engine's own jar, where every
 * pack is fixed when the engine is built, so nothing a tenant or an operator sets later can change which models it allows
 * or which tasks it offers (R-2).
 *
 * @param region the pack's name, such as jp
 * @param models the registry, by model ID
 * @param routes each task's route: the tasks the policy offers, and no other
 * @param policy what the region offers and asks (M3-4-4)
 */
record RegionPack(String region, Map<String, ModelSpec> models, Map<String, Route> routes, Policy policy) {

    RegionPack {
        models = Map.copyOf(models);
        routes = Map.copyOf(routes);
        Objects.requireNonNull(policy, "A region pack has its policy");
    }

    /**
     * What a region offers and asks (blueprint 9.1, 12.2, M3-4-4).
     *
     * @param tasks the tasks the region offers; a request for any other is refused, after identity and before the data
     *     class (the gate of blueprint 9.1)
     * @param aiDisclosure whether an answer must say that an AI wrote it: kept in the policy and checked for its form, and
     *     acted on once an operation needs it
     */
    record Policy(Set<String> tasks, AiDisclosure aiDisclosure) {

        Policy {
            tasks = Set.copyOf(tasks);
            Objects.requireNonNull(aiDisclosure, "A region's policy says whether an answer must say an AI wrote it");
        }
    }

    /** Whether an answer must say that an AI wrote it, by its name in a pack. */
    enum AiDisclosure {
        REQUIRED("required"),
        NOT_REQUIRED("not_required");

        private final String wireName;

        AiDisclosure(String wireName) {
            this.wireName = wireName;
        }

        String wireName() {
            return wireName;
        }
    }
}

package aie.app;

import aie.core.gateway.api.ModelSpec;
import aie.core.gateway.internal.Route;
import java.util.Map;

/**
 * A region pack as the api role loads it (blueprint 12.2): the models this region may use, each with what its contract
 * allows, and each task's route. {@link RegionPacks} reads it from the engine's own jar, where every pack is fixed when
 * the engine is built, so nothing a tenant or an operator sets later can change which models it allows (R-2).
 *
 * @param region the pack's name, such as jp
 * @param models the registry, by model ID
 * @param routes each task's route
 */
record RegionPack(String region, Map<String, ModelSpec> models, Map<String, Route> routes) {

    RegionPack {
        models = Map.copyOf(models);
        routes = Map.copyOf(routes);
    }
}

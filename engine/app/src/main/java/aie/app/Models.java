package aie.app;

import aie.adapters.modelfake.FakeModel;
import aie.core.gateway.api.Gateway;
import aie.core.gateway.internal.Route;
import aie.core.gateway.internal.RoutingGateway;
import aie.ports.ModelPort;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The models of the engine and the routes to them (blueprint 8.3 and 8.4). There is only the fake model so far. When
 * real models come, the registry and the route table move into the region pack, and this class goes.
 */
final class Models {

    /** The task of answering in a conversation, the example of blueprint 2.1. */
    static final String CUSTOMER_CHAT = "customer_chat";
    /** The longest one answer may take. */
    static final Duration ANSWER_TIME = Duration.ofMinutes(2);

    private Models() {
    }

    /** Each model's ID and the adapter that calls it. */
    static Map<String, ModelPort> registry() {
        return Map.of(FakeModel.MODEL, new FakeModel());
    }

    /** Each task's route. */
    static Map<String, Route> routes() {
        return Map.of(CUSTOMER_CHAT, new Route(List.of(FakeModel.MODEL), ANSWER_TIME));
    }

    /** @throws IllegalArgumentException for a route to a model that is not in the registry */
    static Gateway gateway() {
        return new RoutingGateway(registry(), routes());
    }
}

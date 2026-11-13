package aie.core.gateway.internal;

import aie.core.gateway.api.Gateway;
import aie.ports.ModelPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Gateway} by route table (blueprint 8.2 to 8.4). It is built once from the models and the routes, and refuses a
 * route to a model it was not given, so that such a mistake stops the role at startup instead of failing requests
 * later. Every call carries its route's time limit (A-9). A call from inside a database transaction is refused before
 * anything else (A-14). Fallback, retries, budgets and prompts are not here yet.
 */
public final class RoutingGateway implements Gateway {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingGateway.class);

    private final Map<String, ModelPort> models;
    private final Map<String, Route> routes;
    private final Checks checks;
    private final BooleanSupplier insideTx;

    /** With checks that pass everything, since there is no trust layer yet. */
    public RoutingGateway(Map<String, ModelPort> models, Map<String, Route> routes, BooleanSupplier insideTx) {
        this(models, routes, Checks.NONE, insideTx);
    }

    /**
     * @param models the registry: each model's ID and the adapter that calls it
     * @param routes each task's route
     * @param checks the checks on the way to the model and back
     * @param insideTx whether the calling thread is inside a database transaction, asked on every call; the role passes
     *     {@link aie.ports.DatabasePort#insideTx} of its pool
     * @throws IllegalArgumentException for a route to a model that is not in the registry
     */
    public RoutingGateway(
            Map<String, ModelPort> models, Map<String, Route> routes, Checks checks, BooleanSupplier insideTx) {
        Objects.requireNonNull(insideTx, "A gateway needs to know whether a call comes from inside a transaction");
        routes.forEach((task, route) -> route.models().forEach(model -> {
            if (!models.containsKey(model)) {
                throw new IllegalArgumentException(
                        "The route of " + task + " names the model " + model + ", which the gateway was not given");
            }
        }));
        this.models = Map.copyOf(models);
        this.routes = Map.copyOf(routes);
        this.checks = checks;
        this.insideTx = insideTx;
    }

    /**
     * Refuses a call from inside a transaction first, because the input check may come to call a model too. The model's
     * reason for a failure goes to the log alone, with the task, the model and the pieces sent.
     */
    @Override
    public Result answer(Request request, Output output) {
        if (insideTx.getAsBoolean()) {
            throw new IllegalStateException("No model is called inside a database transaction (A-14), so end it first");
        }
        Route route = routeOf(request);
        checks.input(request);
        String model = route.models().getFirst();
        ModelPort.Request call = new ModelPort.Request(model, request.turns().stream().map(RoutingGateway::turn).toList(),
                route.timeout(), request.cancellation()::requested);
        ModelPort.Result result = models.get(model).generate(call, piece -> output.text(checks.output(request.task(), piece)));
        return switch (result) {
            case ModelPort.Completed done -> new Completed(usage(done.usage()));
            case ModelPort.Cancelled cancelled -> new Cancelled(usage(cancelled.usage()));
            case ModelPort.Failed failed -> {
                LOG.warn("The model {} failed the task {} after {} piece(s): {}",
                        model, request.task(), failed.pieces(), failed.reason());
                yield new Failed(failed.pieces(), usage(failed.usage()));
            }
        };
    }

    /** The route of a well-formed request. */
    private Route routeOf(Request request) {
        List<Turn> turns = request.turns();
        if (turns == null || turns.isEmpty()
                || turns.stream().anyMatch(turn -> turn == null || turn.role() == null || turn.text() == null)
                || turns.getLast().role() != Role.USER) {
            throw new IllegalArgumentException("A request's turns each have a role and a text, and end with the user's turn");
        }
        if (request.cancellation() == null) {
            throw new IllegalArgumentException("A request needs a cancellation, even one that never asks to stop");
        }
        Route route = request.task() == null ? null : routes.get(request.task());
        if (route == null) {
            throw new IllegalArgumentException("No route answers the task " + request.task());
        }
        return route;
    }

    private static ModelPort.Turn turn(Turn turn) {
        ModelPort.Role role = switch (turn.role()) {
            case USER -> ModelPort.Role.USER;
            case ASSISTANT -> ModelPort.Role.ASSISTANT;
        };
        return new ModelPort.Turn(role, turn.text());
    }

    private static Usage usage(ModelPort.Usage usage) {
        return new Usage(usage.inputTokens(), usage.outputTokens());
    }
}

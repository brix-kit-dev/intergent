package aie.core.gateway.internal;

import aie.core.LogContext;
import aie.core.gateway.api.Gateway;
import aie.core.trust.api.Audit;
import aie.core.trust.api.Checks;
import aie.core.trust.api.DataClass;
import aie.ports.ModelPort;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * {@link Gateway} by route table (blueprint 8.2 to 8.4). It is built once from the models, the routes and the trust
 * layer's checks, and refuses a route to a model it was not given, so that such a mistake stops the role at startup
 * instead of failing requests later. What the end user said goes through the input check before anything of it is
 * stored, and a model gets it only with the receipt this gateway gave for it (M3-4-1, A-15). Every turn a model is shown
 * is masked by the trust layer first, each time it is sent, and a call's class is at least the one its check found
 * (M3-4-2). What the model writes reaches the caller only through the route's output check, which holds it by the
 * sentence or whole and can stop the answer (M3-4-3). Every call carries its route's time limit (A-9). A call from inside
 * a database transaction is refused before anything else (A-14). The model gets its family's prompt as instructions
 * (M3-2). A call goes to the first model of its route that takes the call's data class, and never to one below it
 * (blueprint 7.3, M3-3-2). The trust layer's audit gets an entry for a blocked text, one before each model call, which
 * the call cannot go without, and one at its end (M3-5-1); each carries the request ID of the calling thread's log
 * context, so an entry and its request's log lines share it. Fallback, retries and budgets are not here yet.
 */
public final class RoutingGateway implements Gateway {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingGateway.class);

    private final Map<String, Model> models;
    private final Map<String, Route> routes;
    /** Each task's ceiling: the highest class a model of its route takes. */
    private final Map<String, DataClass> ceilings;
    private final Checks checks;
    private final Audit audit;
    private final BooleanSupplier insideTx;

    /**
     * @param models the registry: each model's ID, the adapter that calls it and the highest class it takes
     * @param routes each task's route
     * @param checks the trust layer's checks on the way to the model and back (A-15)
     * @param audit the trust layer's audit, which gets an entry before every model call (blueprint 9.4)
     * @param insideTx whether the calling thread is inside a database transaction, asked on every call; the role passes
     *     {@link aie.ports.DatabasePort#insideTx} of its pool
     * @throws IllegalArgumentException for a route to a model that is not in the registry
     */
    public RoutingGateway(
            Map<String, Model> models, Map<String, Route> routes, Checks checks, Audit audit, BooleanSupplier insideTx) {
        Objects.requireNonNull(checks, "A gateway calls no model without the trust layer's checks (A-15)");
        Objects.requireNonNull(audit, "A gateway calls no model without the trust layer's audit (blueprint 9.4)");
        Objects.requireNonNull(insideTx, "A gateway needs to know whether a call comes from inside a transaction");
        routes.forEach((task, route) -> route.models().forEach(model -> {
            if (!models.containsKey(model)) {
                throw new IllegalArgumentException(
                        "The route of " + task + " names the model " + model + ", which the gateway was not given");
            }
        }));
        this.models = Map.copyOf(models);
        this.routes = Map.copyOf(routes);
        Map<String, DataClass> ceilings = new HashMap<>();
        this.routes.forEach((task, route) -> ceilings.put(task, route.models().stream()
                .map(model -> this.models.get(model).maxDataClass()).max(Comparator.naturalOrder()).orElseThrow()));
        this.ceilings = Map.copyOf(ceilings);
        this.checks = checks;
        this.audit = audit;
        this.insideTx = insideTx;
    }

    /**
     * Refuses a call from inside a transaction first, because a check may come to call a service. A blocked text is noted
     * in the log by its rule and task, never by its words, and so is it in the audit.
     */
    @Override
    public InputCheck checkInput(Caller caller, String task, String text) {
        if (insideTx.getAsBoolean()) {
            throw new IllegalStateException("No text is checked inside a database transaction (A-14), so end it first");
        }
        if (caller == null) {
            throw new IllegalArgumentException("The input check needs the caller, whom the audit names");
        }
        if (task == null || !routes.containsKey(task)) {
            throw new IllegalArgumentException("No route answers the task " + task);
        }
        if (text == null) {
            throw new IllegalArgumentException("The input check needs the text the end user said");
        }
        return switch (checks.input(task, text)) {
            case Checks.Passed passed -> new Receipt(this, caller, task, text, passed.dataClass());
            case Checks.Blocked blocked -> {
                LOG.info("The input check blocked a text for the task {} by the rule {}", task, blocked.rule());
                tell(new Audit.Entry(requestId(), caller.tenantId(), caller.applicationId(), caller.endUserId(),
                        caller.conversationId(), null, task, Audit.Event.INPUT_BLOCKED, null, null, null, blocked.rule()));
                yield new Blocked(blocked.rule());
            }
        };
    }

    /**
     * Refuses a call from inside a transaction first. The question was checked when its receipt was made, so it is not
     * checked again (blueprint 9.1); every turn is masked, though, the question as well, since the history is sent again
     * with each answer (M3-4-2). Every piece goes through the answer's output check, with the route's check mode and the
     * model's prompt (M3-4-3). Once the check stops the answer, the model is told to stop as it would be by a cancel, the
     * pieces it still sends are dropped, and the answer ends as blocked, whatever the model says. What is still held when
     * the model completes is checked then; what a model that failed or was cancelled leaves held never goes out. The
     * model's reason for a failure goes to the log alone, with the task, the model and the pieces sent; a stopped answer
     * is logged by its rule. The audit's entry before the call comes once everything else is ready, right before the
     * model is asked; the entry at its end comes once the answer has ended, before the result goes back.
     */
    @Override
    public Result answer(Request request, Output output) {
        if (insideTx.getAsBoolean()) {
            throw new IllegalStateException("No model is called inside a database transaction (A-14), so end it first");
        }
        Route route = routeOf(request);
        Caller caller = ((Receipt) request.question()).caller;
        String model = firstTaking(route, request.dataClass());
        Prompt prompt = route.prompts().get(model);
        Checks.Output check = checks.output(request.task(), route.checks(), prompt.text());
        AtomicReference<String> stopped = new AtomicReference<>();
        ModelPort.Request call = new ModelPort.Request(model, prompt.text(), request.turns().stream().map(this::masked).toList(),
                route.timeout(), () -> stopped.get() != null || request.cancellation().requested());
        audit.add(entry(caller, request, Audit.Event.MODEL_CALLED, model, prompt, null));
        ModelPort.Result outcome = models.get(model).port().generate(call, piece -> {
            if (stopped.get() == null) {
                release(check.piece(piece), output, stopped);
            }
        });
        if (stopped.get() == null && outcome instanceof ModelPort.Completed) {
            release(check.end(), output, stopped);
        }
        Result result = ending(request, model, prompt, outcome, stopped.get());
        tell(entry(caller, request, ended(result), model, prompt, result instanceof OutputBlocked blocked ? blocked.rule() : null));
        return result;
    }

    /** How the answer ended: blocked once the check stopped it, whatever the model said, or as the model ended it. */
    private static Result ending(Request request, String model, Prompt prompt, ModelPort.Result outcome, String stoppedBy) {
        if (stoppedBy != null) {
            LOG.info("The output check stopped the answer of the model {} to the task {} by the rule {}",
                    model, request.task(), stoppedBy);
            return new OutputBlocked(stoppedBy, usage(outcome.usage()));
        }
        return switch (outcome) {
            case ModelPort.Completed done -> new Completed(usage(done.usage()));
            case ModelPort.Cancelled cancelled -> new Cancelled(usage(cancelled.usage()));
            case ModelPort.Failed failed -> {
                LOG.warn("The model {} failed the task {} with the prompt {} after {} piece(s): {}",
                        model, request.task(), prompt.id(), failed.pieces(), failed.reason());
                yield new Failed(failed.pieces(), usage(failed.usage()));
            }
        };
    }

    /** The audit's event for how a call ended. */
    private static Audit.Event ended(Result result) {
        return switch (result) {
            case Completed done -> Audit.Event.COMPLETED;
            case Cancelled cancelled -> Audit.Event.CANCELLED;
            case Failed failed -> Audit.Event.FAILED;
            case OutputBlocked blocked -> Audit.Event.OUTPUT_BLOCKED;
        };
    }

    /** The audit's entry of a call: its caller, the message, the class, the model and its prompt. */
    private static Audit.Entry entry(Caller caller, Request request, Audit.Event event, String model, Prompt prompt, String rule) {
        return new Audit.Entry(requestId(), caller.tenantId(), caller.applicationId(), caller.endUserId(),
                caller.conversationId(), request.messageId(), request.task(), event, request.dataClass(), model, prompt.id(), rule);
    }

    /**
     * Adds an entry that tells what has happened already: a blocked text, or how a call ended. One that cannot be added
     * goes to the log with all of its fields, which hold no text of what anyone said, and what happened stands.
     */
    private void tell(Audit.Entry entry) {
        try {
            audit.add(entry);
        } catch (RuntimeException e) {
            LOG.error("The audit could not add {}, so this line keeps it", entry, e);
        }
    }

    /** The request ID of the calling thread's log context, which its request's log lines carry, or null outside one. */
    private static String requestId() {
        return MDC.get(LogContext.REQUEST_ID);
    }

    @Override
    public DataClass ceiling(String task) {
        DataClass ceiling = task == null ? null : ceilings.get(task);
        if (ceiling == null) {
            throw new IllegalArgumentException("No route answers the task " + task);
        }
        return ceiling;
    }

    /**
     * The route of a well-formed request, whose class is not above the task's ceiling, and whose question has this
     * gateway's receipt for the same task and the same text, with a class no higher than the request's.
     */
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
        if (request.dataClass() == null) {
            throw new IllegalArgumentException("A request needs its data class, which the model has to take");
        }
        if (request.messageId() == null) {
            throw new IllegalArgumentException("A request names the message its answer is written to, for the audit");
        }
        Route route = request.task() == null ? null : routes.get(request.task());
        if (route == null) {
            throw new IllegalArgumentException("No route answers the task " + request.task());
        }
        DataClass ceiling = ceilings.get(request.task());
        if (request.dataClass().compareTo(ceiling) > 0) {
            throw new IllegalArgumentException("No model of the task " + request.task() + " takes " + request.dataClass().wireName()
                    + " data, only up to " + ceiling.wireName() + ", so the caller refuses such a call before anything goes");
        }
        if (!(request.question() instanceof Receipt receipt) || receipt.issuer != this
                || !receipt.task.equals(request.task()) || !receipt.text.equals(turns.getLast().text())) {
            throw new IllegalArgumentException("A question reaches a model only with this gateway's receipt of its input check,"
                    + " for the same task and the same text (A-15)");
        }
        if (request.dataClass().compareTo(receipt.dataClass) < 0) {
            throw new IllegalArgumentException("The question holds " + receipt.dataClass.wireName() + " data, so a call of "
                    + request.dataClass().wireName() + " is too low for it; the caller raises the call first (M3-4-2)");
        }
        return route;
    }

    /** The first model of the route, in its order of preference, that takes this class (blueprint 8.2). */
    private String firstTaking(Route route, DataClass dataClass) {
        return route.models().stream().filter(model -> models.get(model).maxDataClass().compareTo(dataClass) >= 0)
                .findFirst().orElseThrow();
    }

    /** Hands the output what the check released, if anything, or notes the rule that stopped the answer. */
    private static void release(Checks.Verdict verdict, Output output, AtomicReference<String> stopped) {
        switch (verdict) {
            case Checks.Release released when !released.text().isEmpty() -> output.text(released.text());
            case Checks.Release nothing -> {
            }
            case Checks.Stop stop -> stopped.set(stop.rule());
        }
    }

    /** A turn as the model is shown it, with its personal data masked (M3-4-2). */
    private ModelPort.Turn masked(Turn turn) {
        ModelPort.Role role = switch (turn.role()) {
            case USER -> ModelPort.Role.USER;
            case ASSISTANT -> ModelPort.Role.ASSISTANT;
        };
        return new ModelPort.Turn(role, checks.masked(turn.text()));
    }

    private static Usage usage(ModelPort.Usage usage) {
        return new Usage(usage.inputTokens(), usage.outputTokens());
    }

    /**
     * The receipt of a text that passed this gateway's input check. It is private, so only this gateway makes one, and it
     * knows the gateway that made it.
     */
    private static final class Receipt implements Checked {

        private final RoutingGateway issuer;
        private final Caller caller;
        private final String task;
        private final String text;
        private final DataClass dataClass;

        Receipt(RoutingGateway issuer, Caller caller, String task, String text, DataClass dataClass) {
            this.issuer = issuer;
            this.caller = caller;
            this.task = task;
            this.text = text;
            this.dataClass = dataClass;
        }

        @Override
        public Caller caller() {
            return caller;
        }

        @Override
        public String task() {
            return task;
        }

        @Override
        public String text() {
            return text;
        }

        @Override
        public DataClass dataClass() {
            return dataClass;
        }
    }

    /**
     * A model of the registry, as the gateway needs it (blueprint 8.3).
     *
     * @param port the adapter that calls the model
     * @param maxDataClass the highest class of data the model takes, its region pack's {@code max_data_class}
     */
    public record Model(ModelPort port, DataClass maxDataClass) {

        public Model {
            if (port == null || maxDataClass == null) {
                throw new IllegalArgumentException("A model of the gateway needs its adapter and the highest data class it takes");
            }
        }
    }
}

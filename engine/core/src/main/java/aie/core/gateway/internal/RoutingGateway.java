package aie.core.gateway.internal;

import aie.core.LogContext;
import aie.core.gateway.api.Gateway;
import aie.core.gateway.api.ModelSpec;
import aie.core.metering.api.Budgets;
import aie.core.metering.api.Metering;
import aie.core.trust.api.Audit;
import aie.core.trust.api.Checks;
import aie.core.trust.api.DataClass;
import aie.ports.ModelPort;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * context, so an entry and its request's log lines share it. The entry before a call is given the question as it was
 * checked, and the one at its end what the caller got of the answer, for the audit to keep the HMAC of when the call is
 * sensitive (M3-5-3); neither goes to the log. Each call's end is metered too: the tokens the model reports and what they
 * cost by the model's price in the registry (M3-6-1). A tenant that has spent its monthly budget gets no receipt, so no
 * model call (M3-6-2). Fallback and retries are not here yet.
 */
public final class RoutingGateway implements Gateway {

    private static final Logger LOG = LoggerFactory.getLogger(RoutingGateway.class);

    private final Map<String, Model> models;
    private final Map<String, Route> routes;
    /** Each task's ceiling: the highest class a model of its route takes. */
    private final Map<String, DataClass> ceilings;
    private final Checks checks;
    private final Audit audit;
    private final Metering metering;
    private final Budgets budgets;
    /** How many model calls one tenant may have under way on this instance at once (M3-6-3). */
    private final int tenantCalls;
    /** The places each tenant holds among the calls under way here, for every tenant that holds any (M3-6-3). */
    private final ConcurrentHashMap<String, Integer> placesHeld = new ConcurrentHashMap<>();
    private final BooleanSupplier insideTx;

    /**
     * @param models the registry: each model's ID, the adapter that calls it and the highest class it takes
     * @param routes each task's route
     * @param checks the trust layer's checks on the way to the model and back (A-15)
     * @param audit the trust layer's audit, which gets an entry before every model call (blueprint 9.4)
     * @param metering where each model call's use is recorded at its end (M3-6-1)
     * @param budgets where each tenant's monthly budget is asked before its text is checked (M3-6-2)
     * @param tenantCalls how many model calls one tenant may have under way on this instance at once, 1 or more (M3-6-3)
     * @param insideTx whether the calling thread is inside a database transaction, asked on every call; the role passes
     *     {@link aie.ports.DatabasePort#insideTx} of its pool
     * @throws IllegalArgumentException for a route to a model that is not in the registry, or a tenant limit below 1
     */
    public RoutingGateway(Map<String, Model> models, Map<String, Route> routes, Checks checks, Audit audit, Metering metering,
            Budgets budgets, int tenantCalls, BooleanSupplier insideTx) {
        Objects.requireNonNull(checks, "A gateway calls no model without the trust layer's checks (A-15)");
        Objects.requireNonNull(audit, "A gateway calls no model without the trust layer's audit (blueprint 9.4)");
        Objects.requireNonNull(metering, "A gateway calls no model without metering (M3-6-1)");
        Objects.requireNonNull(budgets, "A gateway calls no model without budgets (M3-6-2, T-4)");
        Objects.requireNonNull(insideTx, "A gateway needs to know whether a call comes from inside a transaction");
        if (tenantCalls < 1) {
            throw new IllegalArgumentException("A tenant may have 1 model call or more under way at once, not " + tenantCalls
                    + " (M3-6-3, T-4)");
        }
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
        this.metering = metering;
        this.budgets = budgets;
        this.tenantCalls = tenantCalls;
        this.insideTx = insideTx;
    }

    /**
     * Refuses a call from inside a transaction first, because a check may come to call a service. The tenant's budget
     * comes before the text, so a tenant that has spent it gets no check of its text either; that is noted in the log, by
     * the tenant, the month and the sums. A blocked text is noted in the log by its rule and task, never by its words, and
     * so is it in the audit. A text that passes takes one of the tenant's places among the calls under way here, which its
     * receipt holds (M3-6-3); without a free place it gets none, and the log notes the tenant and its limit. A blocked text
     * takes no place.
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
        Budgets.Standing standing = budgets.standing(caller.tenantId());
        if (!standing.allowsACall()) {
            LOG.info("The tenant {} has spent {} of its monthly budget of {} US dollars in {}, so no model is called for it",
                    caller.tenantId(), standing.spent().toPlainString(), standing.limit().toPlainString(), standing.month());
            return new OverBudget();
        }
        return switch (checks.input(task, text)) {
            case Checks.Passed passed -> {
                if (!hold(caller.tenantId())) {
                    LOG.info("The tenant {} has {} model call(s) under way here, as many as it may have at once, so its text gets"
                            + " no receipt now", caller.tenantId(), tenantCalls);
                    yield new TooManyCalls();
                }
                yield new Receipt(this, caller, task, text, passed.dataClass());
            }
            case Checks.Blocked blocked -> {
                LOG.info("The input check blocked a text for the task {} by the rule {}", task, blocked.rule());
                tell(new Audit.Entry(requestId(), caller.tenantId(), caller.applicationId(), caller.endUserId(),
                        caller.conversationId(), null, task, Audit.Event.INPUT_BLOCKED, null, null, null, blocked.rule()),
                        Audit.Content.NONE);
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
     * model is asked; the entry at its end comes once the answer has ended, before the result goes back. Once the request
     * is found well formed, the call takes one of its tenant's places (M3-6-3): the one the receipt kept since the check,
     * for its first call, or a free one for a later call, which is refused without one. The place goes back when the call
     * returns or throws. A request refused before that leaves the receipt's place kept, for its caller to give back.
     */
    @Override
    public Result answer(Request request, Output output) {
        if (insideTx.getAsBoolean()) {
            throw new IllegalStateException("No model is called inside a database transaction (A-14), so end it first");
        }
        Route route = routeOf(request);
        Receipt question = (Receipt) request.question();
        String tenantId = question.caller.tenantId();
        if (!question.kept.compareAndSet(true, false) && !hold(tenantId)) {
            throw new IllegalStateException("The tenant " + tenantId + " has " + tenantCalls + " model call(s) under way here, as"
                    + " many as it may have at once, so a later call with this receipt waits until one of them ends (M3-6-3)");
        }
        try {
            return answer(request, output, route, question);
        } finally {
            giveBack(tenantId);
        }
    }

    /** The call itself, once it holds a place of its tenant: the place goes back when it returns, however it returns. */
    private Result answer(Request request, Output output, Route route, Receipt question) {
        Caller caller = question.caller;
        String model = firstTaking(route, request.dataClass());
        Prompt prompt = route.prompts().get(model);
        Checks.Output check = checks.output(request.task(), route.checks(), prompt.text());
        AtomicReference<String> stopped = new AtomicReference<>();
        StringBuffer released = new StringBuffer();
        ModelPort.Request call = new ModelPort.Request(model, prompt.text(), request.turns().stream().map(this::masked).toList(),
                route.timeout(), () -> stopped.get() != null || request.cancellation().requested());
        audit.add(entry(caller, request, Audit.Event.MODEL_CALLED, model, prompt, null), new Audit.Content(question.text, null));
        ModelPort.Result outcome = models.get(model).port().generate(call, piece -> {
            if (stopped.get() == null) {
                release(check.piece(piece), output, stopped, released);
            }
        });
        if (stopped.get() == null && outcome instanceof ModelPort.Completed) {
            release(check.end(), output, stopped, released);
        }
        Result result = ending(request, model, prompt, outcome, stopped.get());
        meter(new Metering.Use(requestId(), caller.tenantId(), caller.applicationId(), request.messageId(), request.task(), model,
                result.usage().inputTokens(), result.usage().outputTokens(),
                models.get(model).price().cost(result.usage().inputTokens(), result.usage().outputTokens())));
        tell(entry(caller, request, ended(result), model, prompt, result instanceof OutputBlocked blocked ? blocked.rule() : null),
                new Audit.Content(null, released.toString()));
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
     * goes to the log with all of its fields, which hold no text of what anyone said, and what happened stands. Its
     * content never goes to the log.
     */
    private void tell(Audit.Entry entry, Audit.Content content) {
        try {
            audit.add(entry, content);
        } catch (RuntimeException e) {
            LOG.error("The audit could not add {}, so this line keeps it", entry, e);
        }
    }

    /**
     * Records what a call used, once it has ended, however it ended. One that cannot be recorded goes to the log with all
     * of its fields, as an audit entry of what happened does, and what happened stands.
     */
    private void meter(Metering.Use use) {
        try {
            metering.record(use);
        } catch (RuntimeException e) {
            LOG.error("Metering could not record {}, so this line keeps it", use, e);
        }
    }

    /**
     * Takes one of the tenant's places among the calls under way here, if it has one free (M3-6-3). The map changes one
     * tenant at a time, so two checks at once never take more places than the tenant has.
     */
    private boolean hold(String tenantId) {
        boolean[] held = new boolean[1];
        placesHeld.compute(tenantId, (tenant, places) -> {
            int now = places == null ? 0 : places;
            if (now >= tenantCalls) {
                return places;
            }
            held[0] = true;
            return now + 1;
        });
        return held[0];
    }

    /** Gives one of the tenant's places back; a tenant that holds none is no longer kept. */
    private void giveBack(String tenantId) {
        placesHeld.computeIfPresent(tenantId, (tenant, places) -> places <= 1 ? null : places - 1);
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

    /**
     * Hands the output what the check released, if anything, and keeps it with what went out before, or notes the rule
     * that stopped the answer.
     */
    private static void release(Checks.Verdict verdict, Output output, AtomicReference<String> stopped, StringBuffer released) {
        switch (verdict) {
            case Checks.Release piece when !piece.text().isEmpty() -> {
                output.text(piece.text());
                released.append(piece.text());
            }
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
     * knows the gateway that made it. It keeps one of its tenant's places for its first call, from the check until that
     * call starts or its caller gives it back (M3-6-3).
     */
    private static final class Receipt implements Checked {

        private final RoutingGateway issuer;
        /** Whether it still keeps the place its tenant took for it at the check (M3-6-3). */
        private final AtomicBoolean kept = new AtomicBoolean(true);
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

        @Override
        public void release() {
            if (kept.compareAndSet(true, false)) {
                issuer.giveBack(caller.tenantId());
            }
        }
    }

    /**
     * A model of the registry, as the gateway needs it (blueprint 8.3).
     *
     * @param port the adapter that calls the model
     * @param maxDataClass the highest class of data the model takes, its region pack's {@code max_data_class}
     * @param price what a million tokens cost, its region pack's {@code price}, which metering counts from (M3-6-1)
     */
    public record Model(ModelPort port, DataClass maxDataClass, ModelSpec.Price price) {

        public Model {
            if (port == null || maxDataClass == null || price == null) {
                throw new IllegalArgumentException("A model of the gateway needs its adapter, the highest data class it takes"
                        + " and its price");
            }
        }
    }
}

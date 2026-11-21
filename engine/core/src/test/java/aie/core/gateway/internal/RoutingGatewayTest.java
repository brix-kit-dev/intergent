package aie.core.gateway.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.LogContext;
import aie.core.gateway.api.Gateway;
import aie.core.gateway.api.Gateway.Blocked;
import aie.core.gateway.api.Gateway.Cancelled;
import aie.core.gateway.api.Gateway.Checked;
import aie.core.gateway.api.Gateway.Completed;
import aie.core.gateway.api.Gateway.Failed;
import aie.core.gateway.api.Gateway.Request;
import aie.core.gateway.api.Gateway.Result;
import aie.core.gateway.api.Gateway.Role;
import aie.core.gateway.api.Gateway.Turn;
import aie.core.gateway.api.Gateway.Usage;
import aie.core.gateway.api.ModelSpec;
import aie.core.metering.api.Metering;
import aie.core.trust.api.Audit;
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.Checks;
import aie.core.trust.api.DataClass;
import aie.ports.ModelPort;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class RoutingGatewayTest {

    private static final Gateway.Cancellation NEVER = () -> false;
    /** Who the tests' calls are for (M3-5-1). */
    private static final Gateway.Caller CALLER = new Gateway.Caller("ten_a", "app_a", "eu_a", "conv_a");
    /** The message the tests' answers are written to. */
    private static final String MESSAGE = "msg_a";
    /** What the tests' models cost: 3 dollars a million tokens in, 15 out. */
    private static final ModelSpec.Price PRICE = new ModelSpec.Price(new BigDecimal("3"), new BigDecimal("15"));
    /** Not inside a database transaction. */
    private static final BooleanSupplier OUTSIDE = () -> false;
    private static final Prompt PROMPT = new Prompt("chat/test/v1", "Answer the test.");
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final ModelPort.Result DONE = new ModelPort.Completed(new ModelPort.Usage(5, 2));
    /** An output check that lets every piece out as it came. */
    private static final Checks.Output AS_IT_IS = new Checks.Output() {
        @Override
        public Checks.Verdict piece(String piece) {
            return new Checks.Release(piece);
        }

        @Override
        public Checks.Verdict end() {
            return new Checks.Release("");
        }
    };
    /** Checks that pass every text and every piece as it is, and find no personal data. */
    private static final Checks PASSING = new Checks() {
        @Override
        public Input input(String task, String text) {
            return new Passed(DataClass.PUBLIC);
        }

        @Override
        public String masked(String text) {
            return text;
        }

        @Override
        public Output output(String task, CheckMode mode, String instructions) {
            return AS_IT_IS;
        }
    };

    /** What happened, in order: the checks, the models' calls and the pieces the caller got. */
    private final List<String> events = new CopyOnWriteArrayList<>();
    /** The calls the models got. */
    private final List<ModelPort.Request> calls = new CopyOnWriteArrayList<>();
    /** The entries the gateway added to the audit, in order. */
    private final List<Audit.Entry> audited = new CopyOnWriteArrayList<>();
    /** The content each entry came with, in the same order. */
    private final List<Audit.Content> contents = new CopyOnWriteArrayList<>();
    /** What metering recorded, in order. */
    private final List<Metering.Use> metered = new CopyOnWriteArrayList<>();
    /** Metering, which keeps each use it gets. */
    private final Metering metering = metered::add;
    /** The audit, which keeps each entry it gets and the content it came with. */
    private final Audit audit = (entry, content) -> {
        audited.add(entry);
        contents.add(content);
    };

    /** A model that takes every class, notes its call, sends the pieces and ends with the result. */
    private RoutingGateway.Model model(String name, ModelPort.Result result, String... pieces) {
        return model(name, DataClass.SENSITIVE, result, pieces);
    }

    /** A model that takes data up to this class, notes its call, sends the pieces and ends with the result. */
    private RoutingGateway.Model model(String name, DataClass max, ModelPort.Result result, String... pieces) {
        return new RoutingGateway.Model((request, output) -> {
            events.add("model " + name);
            calls.add(request);
            for (String piece : pieces) {
                output.text(piece);
            }
            return result;
        }, max, PRICE);
    }

    /** The same test prompt for each model, or none for a list a route refuses anyway. */
    static Map<String, Prompt> prompts(List<String> models) {
        Map<String, Prompt> prompts = new HashMap<>();
        if (models != null) {
            models.stream().filter(Objects::nonNull).forEach(model -> prompts.put(model, PROMPT));
        }
        return prompts;
    }

    private static Route route(String... models) {
        return new Route(List.of(models), MINUTE, CheckMode.SENTENCE, prompts(List.of(models)));
    }

    /** The gateway's receipt for a text it passed, as a request for that text needs it (M3-4-1). */
    private static Checked checked(Gateway gateway, String task, String text) {
        return (Checked) gateway.checkInput(CALLER, task, text);
    }

    /** One turn of the user, of public data, checked by the gateway first. */
    private static Request ask(Gateway gateway, String task, String said) {
        return new Request(task, List.of(new Turn(Role.USER, said)), NEVER, DataClass.PUBLIC, checked(gateway, task, said), MESSAGE);
    }

    private Result answer(Gateway gateway, Request request) {
        return gateway.answer(request, piece -> events.add("caller " + piece));
    }

    @Test
    void itAnswersWithTheFirstModelOfTheTasksRoute() {
        Gateway gateway = new RoutingGateway(
                Map.of("first", model("first", DONE, "a", "b"), "second", model("second", DONE, "x")),
                Map.of("chat", route("first", "second")), PASSING, audit, metering, OUTSIDE);

        Result result = answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(events).containsExactly("model first", "caller a", "caller b");
        assertThat(calls).singleElement().extracting(ModelPort.Request::model).isEqualTo("first");
        assertThat(result).isEqualTo(new Completed(new Usage(5, 2)));
    }

    /**
     * M3-3-2: a call goes to the first model of its route, in the route's order, that takes the call's class. A model below
     * the class is passed over, and a lower model later in the route does not win over an earlier one that takes it.
     */
    @Test
    void aCallGoesToTheFirstModelThatTakesItsClass() {
        Gateway gateway = new RoutingGateway(Map.of(
                        "internal", model("internal", DataClass.INTERNAL, DONE),
                        "sensitive", model("sensitive", DataClass.SENSITIVE, DONE),
                        "public", model("public", DataClass.PUBLIC, DONE)),
                Map.of("chat", route("internal", "sensitive", "public")), PASSING, audit, metering, OUTSIDE);
        Checked hello = checked(gateway, "chat", "Hello");

        for (DataClass dataClass : DataClass.values()) {
            answer(gateway, new Request("chat", List.of(new Turn(Role.USER, "Hello")), NEVER, dataClass, hello, MESSAGE));
        }

        assertThat(calls).extracting(ModelPort.Request::model).containsExactly("internal", "internal", "sensitive", "sensitive");
    }

    /** M3-3-2: a task's ceiling is the highest class a model of its route takes, wherever that model stands in it. */
    @Test
    void aTasksCeilingIsTheHighestClassOfItsRoute() {
        Gateway gateway = new RoutingGateway(Map.of(
                        "public", model("public", DataClass.PUBLIC, DONE),
                        "internal", model("internal", DataClass.INTERNAL, DONE),
                        "personal", model("personal", DataClass.PERSONAL, DONE),
                        "sensitive", model("sensitive", DataClass.SENSITIVE, DONE)),
                Map.of("chat", route("public"), "translate", route("internal", "public"), "search", route("public", "personal"),
                        "care", route("public", "sensitive", "internal")), PASSING, audit, metering, OUTSIDE);

        assertThat(gateway.ceiling("chat")).isEqualTo(DataClass.PUBLIC);
        assertThat(gateway.ceiling("translate")).isEqualTo(DataClass.INTERNAL);
        assertThat(gateway.ceiling("search")).isEqualTo(DataClass.PERSONAL);
        assertThat(gateway.ceiling("care")).isEqualTo(DataClass.SENSITIVE);
        assertThatThrownBy(() -> gateway.ceiling("summarize")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No route answers the task summarize");
        assertThatThrownBy(() -> gateway.ceiling(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }

    /**
     * M3-3-2: a call above its task's ceiling goes to no model, not even to one of a lower class: the caller should have
     * refused it, so it is a mistake, refused before the model. One at the ceiling is answered.
     */
    @Test
    void aCallAboveTheCeilingReachesNoModel() {
        Gateway gateway = new RoutingGateway(Map.of("low", model("low", DataClass.INTERNAL, DONE, "a")),
                Map.of("chat", route("low")), noting(), audit, metering, OUTSIDE);
        Turn hello = new Turn(Role.USER, "Hello");
        Checked checked = checked(gateway, "chat", "Hello");

        for (DataClass above : List.of(DataClass.PERSONAL, DataClass.SENSITIVE)) {
            assertThatThrownBy(() -> answer(gateway, new Request("chat", List.of(hello), NEVER, above, checked, MESSAGE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No model of the task chat takes " + above.wireName()
                            + " data, only up to internal, so the caller refuses such a call before anything goes");
        }
        assertThat(events).containsExactly("input chat Hello");
        assertThat(audited).isEmpty();
        assertThat(answer(gateway, new Request("chat", List.of(hello), NEVER, DataClass.INTERNAL, checked, MESSAGE)))
                .isInstanceOf(Completed.class);
        assertThat(events).containsExactly("input chat Hello", "model low", "output chat a", "caller A");
    }

    @Test
    void aModelOfTheGatewayNeedsItsAdapterAndItsHighestClass() {
        ModelPort port = (request, output) -> DONE;

        assertThatThrownBy(() -> new RoutingGateway.Model(null, DataClass.PUBLIC, PRICE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A model of the gateway needs its adapter, the highest data class it takes and its price");
        assertThatThrownBy(() -> new RoutingGateway.Model(port, null, PRICE)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A model of the gateway needs its adapter, the highest data class it takes and its price");
        assertThatThrownBy(() -> new RoutingGateway.Model(port, DataClass.PUBLIC, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A model of the gateway needs its adapter, the highest data class it takes and its price");
    }

    @Test
    void eachTaskGoesToItsOwnRoute() {
        Gateway gateway = new RoutingGateway(
                Map.of("one", model("one", DONE), "two", model("two", DONE)),
                Map.of("chat", route("one"), "translate", route("two")), PASSING, audit, metering, OUTSIDE);

        answer(gateway, ask(gateway, "translate", "Hello"));
        answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(events).containsExactly("model two", "model one");
    }

    /** A-9: no call goes out without a time limit, and the limit is the route's. */
    @Test
    void everyCallCarriesTheTimeOfItsRoute() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of(
                "quick", new Route(List.of("m"), Duration.ofSeconds(3), CheckMode.SENTENCE, prompts(List.of("m"))),
                "slow", new Route(List.of("m"), Duration.ofSeconds(90), CheckMode.SENTENCE, prompts(List.of("m")))),
                PASSING, audit, metering, OUTSIDE);

        answer(gateway, ask(gateway, "quick", "Hello"));
        answer(gateway, ask(gateway, "slow", "Hello"));

        assertThat(calls).extracting(ModelPort.Request::timeout).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(90));
    }

    @Test
    void theModelGetsTheConversationAndTheCancel() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), PASSING, audit, metering, OUTSIDE);
        AtomicBoolean cancel = new AtomicBoolean();

        answer(gateway, new Request("chat", List.of(new Turn(Role.USER, "Hi"), new Turn(Role.ASSISTANT, "Hello"),
                new Turn(Role.USER, "Bye")), cancel::get, DataClass.PUBLIC, checked(gateway, "chat", "Bye"), MESSAGE));

        ModelPort.Request call = calls.getFirst();
        assertThat(call.instructions()).isEqualTo("Answer the test.");
        assertThat(call.turns()).containsExactly(new ModelPort.Turn(ModelPort.Role.USER, "Hi"),
                new ModelPort.Turn(ModelPort.Role.ASSISTANT, "Hello"), new ModelPort.Turn(ModelPort.Role.USER, "Bye"));
        assertThat(call.cancellation().requested()).isFalse();
        cancel.set(true);
        assertThat(call.cancellation().requested()).isTrue();
    }

    @Test
    void aCancelOrAFailureComesBackWithTheUsageSoFar() {
        Gateway gateway = new RoutingGateway(Map.of(
                "stops", model("stops", new ModelPort.Cancelled(new ModelPort.Usage(4, 1))),
                "breaks", model("breaks", new ModelPort.Failed("the model's own words", 2, new ModelPort.Usage(4, 6)))),
                Map.of("stop", route("stops"), "break", route("breaks")), PASSING, audit, metering, OUTSIDE);

        assertThat(answer(gateway, ask(gateway, "stop", "Hello"))).isEqualTo(new Cancelled(new Usage(4, 1)));
        assertThat(answer(gateway, ask(gateway, "break", "Hello"))).isEqualTo(new Failed(2, new Usage(4, 6)));
    }

    /** The model's reason is for the logs; the result has no place for it, so it cannot reach the user. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailureIsLoggedWithTheModelsReason(CapturedOutput output) {
        Gateway gateway = new RoutingGateway(
                Map.of("m", model("m", new ModelPort.Failed("the vendor is out of quota", 1, new ModelPort.Usage(5, 3)), "a")),
                Map.of("chat", route("m")), PASSING, audit, metering, OUTSIDE);

        Result result = answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(output).contains("The model m failed the task chat with the prompt chat/test/v1 after 1 piece(s): the vendor is out of quota");
        assertThat(result).hasToString("Failed[pieces=1, usage=Usage[inputTokens=5, outputTokens=3]]");
    }

    /** A task without a route is a mistake, whether its text is checked or answered, and no check or model sees it. */
    @Test
    void aTaskWithoutARouteIsAMistake() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), audit, metering, OUTSIDE);
        Checked hello = checked(gateway, "chat", "Hello");
        events.clear();

        assertThatThrownBy(() -> gateway.checkInput(CALLER, "translate", "Hello"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No route answers the task translate");
        assertThatThrownBy(() -> gateway.checkInput(CALLER, null, "Hello")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> answer(gateway, new Request("translate", List.of(new Turn(Role.USER, "Hello")), NEVER,
                DataClass.PUBLIC, hello, MESSAGE))).isInstanceOf(IllegalArgumentException.class).hasMessage("No route answers the task translate");
        assertThatThrownBy(() -> answer(gateway, new Request(null, List.of(new Turn(Role.USER, "Hello")), NEVER,
                DataClass.PUBLIC, hello, MESSAGE))).isInstanceOf(IllegalArgumentException.class);
        assertThat(events).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void aRequestThatIsNotWellFormedIsAMistake() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), audit, metering, OUTSIDE);
        Checked checked = checked(gateway, "chat", "Hello");
        events.clear();
        Turn hello = new Turn(Role.USER, "Hello");
        List<Request> wrong = List.of(
                new Request("chat", List.of(), NEVER, DataClass.PUBLIC, checked, MESSAGE),
                new Request("chat", null, NEVER, DataClass.PUBLIC, checked, MESSAGE),
                new Request("chat", List.of(hello, new Turn(Role.ASSISTANT, "Hi")), NEVER, DataClass.PUBLIC, checked, MESSAGE),
                new Request("chat", List.of(new Turn(Role.USER, null)), NEVER, DataClass.PUBLIC, checked, MESSAGE),
                new Request("chat", List.of(new Turn(null, "Hello")), NEVER, DataClass.PUBLIC, checked, MESSAGE),
                new Request("chat", Arrays.asList(null, hello), NEVER, DataClass.PUBLIC, checked, MESSAGE),
                new Request("chat", List.of(hello), null, DataClass.PUBLIC, checked, MESSAGE),
                new Request("chat", List.of(hello), NEVER, null, checked, MESSAGE));

        for (Request request : wrong) {
            assertThatThrownBy(() -> answer(gateway, request)).as(request.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(events).isEmpty();
    }

    /**
     * Blueprint 8.2 and 9.1: the input check comes before the model, once, when the text is checked; the answer does not
     * check it again. The caller sees only what the output check passed.
     */
    @Test
    void theChecksStandBetweenTheCallerAndTheModel() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a", "b")), Map.of("chat", route("m")), noting(), audit, metering, OUTSIDE);

        answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(events).containsExactly("input chat Hello", "model m", "output chat a", "caller A", "output chat b", "caller B");
    }

    /** M3-4-1: a text that passes comes back as the gateway's receipt for that task and that very text. */
    @Test
    void aTextThatPassesComesBackAsItsReceipt() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), audit, metering, OUTSIDE);

        Gateway.InputCheck check = gateway.checkInput(CALLER, "chat", "Hello there");

        assertThat(check).isInstanceOf(Checked.class);
        assertThat(((Checked) check).task()).isEqualTo("chat");
        assertThat(((Checked) check).text()).isEqualTo("Hello there");
        assertThat(events).containsExactly("input chat Hello there");
        assertThat(calls).isEmpty();
    }

    /**
     * M3-4-1: a text the input check blocks comes back blocked, by the check's rule, with no receipt, so it can reach no
     * model. The log names the rule and the task, never the words.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aTextTheInputCheckBlocksReachesNoModel(CapturedOutput output) {
        Checks blocking = new Checks() {
            @Override
            public Input input(String task, String text) {
                events.add("input " + task + " " + text);
                return new Checks.Blocked("test_rule");
            }

            @Override
            public String masked(String text) {
                return text;
            }

            @Override
            public Output output(String task, CheckMode mode, String instructions) {
                return AS_IT_IS;
            }
        };
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), blocking, audit, metering, OUTSIDE);

        assertThat(gateway.checkInput(CALLER, "chat", "the secret words")).isEqualTo(new Blocked("test_rule"));

        assertThat(events).containsExactly("input chat the secret words");
        assertThat(calls).isEmpty();
        assertThat(output).contains("The input check blocked a text for the task chat by the rule test_rule")
                .doesNotContain("the secret words");
    }

    /** An exception of the input check reaches the caller, and no receipt comes of it. */
    @Test
    void aCheckThatFailsGivesNoReceipt() {
        Checks failing = new Checks() {
            @Override
            public Input input(String task, String text) {
                throw new IllegalStateException("not this one");
            }

            @Override
            public String masked(String text) {
                return text;
            }

            @Override
            public Output output(String task, CheckMode mode, String instructions) {
                return AS_IT_IS;
            }
        };
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), failing, audit, metering, OUTSIDE);

        assertThatThrownBy(() -> gateway.checkInput(CALLER, "chat", "Hello")).hasMessage("not this one");
        assertThat(calls).isEmpty();
    }

    @Test
    void theInputCheckNeedsAText() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), audit, metering, OUTSIDE);

        assertThatThrownBy(() -> gateway.checkInput(CALLER, "chat", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The input check needs the text the end user said");
        assertThat(events).isEmpty();
    }

    /**
     * A-15: a question reaches a model only with this gateway's own receipt for it: not without one, not with one made
     * elsewhere that says the same, not with another gateway's, and not with one for another task or another text, such as
     * an earlier turn's. Nothing is checked or called then.
     */
    @Test
    void aQuestionWithoutThisGatewaysReceiptReachesNoModel() {
        Map<String, RoutingGateway.Model> models = Map.of("m", model("m", DONE));
        Map<String, Route> routes = Map.of("chat", route("m"), "translate", route("m"));
        Gateway gateway = new RoutingGateway(models, routes, noting(), audit, metering, OUTSIDE);
        Gateway other = new RoutingGateway(models, routes, noting(), audit, metering, OUTSIDE);
        Checked made = new Checked() {
            @Override
            public Gateway.Caller caller() {
                return CALLER;
            }

            @Override
            public String task() {
                return "chat";
            }

            @Override
            public String text() {
                return "Bye";
            }

            @Override
            public DataClass dataClass() {
                return DataClass.PUBLIC;
            }
        };
        List<Checked> wrong = Arrays.asList(null, made, checked(other, "chat", "Bye"), checked(gateway, "translate", "Bye"),
                checked(gateway, "chat", "Hi"), checked(gateway, "chat", "bye"));
        List<Turn> turns = List.of(new Turn(Role.USER, "Hi"), new Turn(Role.ASSISTANT, "Hello"), new Turn(Role.USER, "Bye"));
        events.clear();

        for (Checked receipt : wrong) {
            assertThatThrownBy(() -> answer(gateway, new Request("chat", turns, NEVER, DataClass.PUBLIC, receipt, MESSAGE)))
                    .as(String.valueOf(receipt)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A question reaches a model only with this gateway's receipt of its input check, for the same task"
                            + " and the same text (A-15)");
        }
        assertThat(events).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(audited).isEmpty();
        assertThat(answer(gateway, new Request("chat", turns, NEVER, DataClass.PUBLIC, checked(gateway, "chat", "Bye"), MESSAGE)))
                .isInstanceOf(Completed.class);
    }

    /** Blueprint 9.1: the same text is checked once, so its receipt serves every call for it, each without a check. */
    @Test
    void aReceiptServesEveryCallForItsText() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), audit, metering, OUTSIDE);
        Request request = ask(gateway, "chat", "Hello");

        answer(gateway, request);
        answer(gateway, request);

        assertThat(events).containsExactly("input chat Hello", "model m", "model m");
    }

    /**
     * M3-4-2: every turn a model is shown is masked by the trust layer, the history as well as the question, each time it
     * is sent. The request keeps its turns as they were.
     */
    @Test
    void theModelIsShownEveryTurnMasked() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), masking(DataClass.PUBLIC), audit, metering,
                OUTSIDE);
        List<Turn> turns = List.of(new Turn(Role.USER, "Call 0901"), new Turn(Role.ASSISTANT, "On 0901?"),
                new Turn(Role.USER, "Yes, 0901"));

        answer(gateway, new Request("chat", turns, NEVER, DataClass.PUBLIC, checked(gateway, "chat", "Yes, 0901"), MESSAGE));
        answer(gateway, new Request("chat", turns, NEVER, DataClass.PUBLIC, checked(gateway, "chat", "Yes, 0901"), MESSAGE));

        assertThat(calls).hasSize(2).allSatisfy(call -> assertThat(call.turns()).containsExactly(
                new ModelPort.Turn(ModelPort.Role.USER, "Call [n]"), new ModelPort.Turn(ModelPort.Role.ASSISTANT, "On [n]?"),
                new ModelPort.Turn(ModelPort.Role.USER, "Yes, [n]")));
        assertThat(turns.getLast().text()).isEqualTo("Yes, 0901");
    }

    /**
     * M3-4-2: the receipt carries the class its check found, and a call below it reaches no model: the caller raises its
     * call first. One at that class or above is answered, masked.
     */
    @Test
    void aCallBelowTheClassItsCheckFoundReachesNoModel() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), masking(DataClass.PERSONAL), audit, metering,
                OUTSIDE);
        Checked checked = checked(gateway, "chat", "My number is 0901");
        List<Turn> turns = List.of(new Turn(Role.USER, "My number is 0901"));

        assertThat(checked.dataClass()).isEqualTo(DataClass.PERSONAL);
        for (DataClass below : List.of(DataClass.PUBLIC, DataClass.INTERNAL)) {
            assertThatThrownBy(() -> answer(gateway, new Request("chat", turns, NEVER, below, checked, MESSAGE)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The question holds personal data, so a call of " + below.wireName()
                            + " is too low for it; the caller raises the call first (M3-4-2)");
        }
        assertThat(calls).isEmpty();
        assertThat(answer(gateway, new Request("chat", turns, NEVER, DataClass.PERSONAL, checked, MESSAGE))).isInstanceOf(Completed.class);
        assertThat(answer(gateway, new Request("chat", turns, NEVER, DataClass.SENSITIVE, checked, MESSAGE))).isInstanceOf(Completed.class);
        assertThat(calls).extracting(call -> call.turns().getLast().text()).containsExactly("My number is [n]", "My number is [n]");
    }

    /** A receipt carries the class its check found: public for a text without personal data. */
    @Test
    void aReceiptCarriesTheClassItsCheckFound() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), PASSING, audit, metering, OUTSIDE);

        assertThat(checked(gateway, "chat", "Hello").dataClass()).isEqualTo(DataClass.PUBLIC);
    }

    /** M3-4-3: each answer's output check gets the task, the route's check mode and the prompt of the model that answers. */
    @Test
    void theOutputCheckGetsTheRoutesModeAndTheModelsPrompt() {
        List<String> opened = new CopyOnWriteArrayList<>();
        Checks recording = outputs((task, mode, instructions) -> {
            opened.add(task + " " + mode + " " + instructions);
            return AS_IT_IS;
        });
        Map<String, Prompt> prompts = Map.of("first", new Prompt("chat/first/v2", "First family."),
                "second", new Prompt("chat/second/v1", "Second family."));
        Gateway gateway = new RoutingGateway(Map.of("first", model("first", DONE), "second", model("second", DONE)),
                Map.of("chat", new Route(List.of("first", "second"), MINUTE, CheckMode.FULL, prompts),
                        "embed", new Route(List.of("second"), MINUTE, CheckMode.INPUT, Map.of("second", prompts.get("second")))),
                recording, audit, metering, OUTSIDE);

        answer(gateway, ask(gateway, "chat", "Hello"));
        answer(gateway, ask(gateway, "embed", "Hello"));

        assertThat(opened).containsExactly("chat FULL First family.", "embed INPUT Second family.");
    }

    /**
     * M3-4-3: the caller gets what the check lets out, when it lets it out, and nothing for an empty release; what is
     * still held goes out once the model has completed.
     */
    @Test
    void whatTheCheckHoldsGoesOutWhenTheModelCompletes() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a", "b", "c")), Map.of("chat", route("m")),
                outputs((task, mode, instructions) -> holding(new StringBuilder())), audit, metering, OUTSIDE);

        Result result = answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(events).containsExactly("model m", "caller abc");
        assertThat(result).isEqualTo(new Completed(new Usage(5, 2)));
    }

    /**
     * M3-4-3: a stop ends the answer as blocked: the model is told to stop as by a cancel, the caller keeps only what went
     * out before, and the log names the rule and never the text.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aStopTellsTheModelToStopAndEndsTheAnswerBlocked(CapturedOutput log) {
        List<String> checked = new CopyOnWriteArrayList<>();
        Gateway gateway = new RoutingGateway(Map.of("m", new RoutingGateway.Model(listening("a", "secret b", "c"), DataClass.SENSITIVE, PRICE)),
                Map.of("chat", route("m")), outputs((task, mode, instructions) -> stoppingAt("secret", checked)), audit, metering, OUTSIDE);

        Result result = answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(result).isEqualTo(new Gateway.OutputBlocked("test_rule", new Usage(5, 2)));
        assertThat(events).containsExactly("caller a", "model stopped");
        assertThat(checked).containsExactly("a", "secret b");
        assertThat(log).contains("The output check stopped the answer of the model m to the task chat by the rule test_rule")
                .doesNotContain("secret b");
    }

    /**
     * M3-4-3: a model that goes on after the stop is not heard: its pieces reach neither the check nor the caller, and a
     * completion after the stop still ends the answer blocked, with the model's usage, and nothing held goes out.
     */
    @Test
    void aModelThatGoesOnAfterTheStopIsNotHeard() {
        List<String> checked = new CopyOnWriteArrayList<>();
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a", "secret b", "c", "d")), Map.of("chat", route("m")),
                outputs((task, mode, instructions) -> stoppingAt("secret", checked)), audit, metering, OUTSIDE);

        Result result = answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(result).isEqualTo(new Gateway.OutputBlocked("test_rule", new Usage(5, 2)));
        assertThat(checked).containsExactly("a", "secret b");
        assertThat(events).containsExactly("model m", "caller a");
    }

    /** M3-4-3: what the check holds when the model fails or is cancelled never goes out, and the result is the model's. */
    @Test
    void whatIsHeldWhenTheModelFailsOrIsCancelledNeverGoesOut() {
        Gateway gateway = new RoutingGateway(Map.of(
                        "breaks", model("breaks", new ModelPort.Failed("the model's own words", 2, new ModelPort.Usage(4, 6)), "a", "b"),
                        "stops", model("stops", new ModelPort.Cancelled(new ModelPort.Usage(4, 1)), "a")),
                Map.of("break", route("breaks"), "stop", route("stops")),
                outputs((task, mode, instructions) -> holding(new StringBuilder())), audit, metering, OUTSIDE);

        assertThat(answer(gateway, ask(gateway, "break", "Hello"))).isEqualTo(new Failed(2, new Usage(4, 6)));
        assertThat(answer(gateway, ask(gateway, "stop", "Hello"))).isEqualTo(new Cancelled(new Usage(4, 1)));
        assertThat(events).containsExactly("model breaks", "model stops");
    }

    /** M3-4-3: a check that stops the answer at its end, once the model completed, ends it blocked with the usage. */
    @Test
    void aStopAtTheEndEndsTheAnswerBlocked() {
        Checks.Output stopsAtTheEnd = new Checks.Output() {
            @Override
            public Checks.Verdict piece(String piece) {
                return new Checks.Release("");
            }

            @Override
            public Checks.Verdict end() {
                return new Checks.Stop("test_rule");
            }
        };
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")),
                outputs((task, mode, instructions) -> stopsAtTheEnd), audit, metering, OUTSIDE);

        assertThat(answer(gateway, ask(gateway, "chat", "Hello"))).isEqualTo(new Gateway.OutputBlocked("test_rule", new Usage(5, 2)));
        assertThat(events).containsExactly("model m");
    }

    /**
     * A-14: from inside a transaction nothing is checked, chosen or called. Even a task without a route gets this answer,
     * which shows that the gateway asks before anything else.
     */
    @Test
    void aCallFromInsideATransactionIsRefusedBeforeAnything() {
        AtomicBoolean inside = new AtomicBoolean();
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), noting(), audit, metering, inside::get);
        Request request = ask(gateway, "chat", "Hello");
        Request unrouted = new Request("translate", request.turns(), NEVER, DataClass.PUBLIC, request.question(), MESSAGE);
        events.clear();
        inside.set(true);

        assertThatThrownBy(() -> answer(gateway, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No model is called inside a database transaction (A-14), so end it first");
        assertThatThrownBy(() -> answer(gateway, unrouted)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.checkInput(CALLER, "chat", "Hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No text is checked inside a database transaction (A-14), so end it first");
        assertThatThrownBy(() -> gateway.checkInput(CALLER, "translate", "Hello")).isInstanceOf(IllegalStateException.class);
        assertThat(events).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(audited).isEmpty();
    }

    /** One gateway serves threads in and out of transactions, so it asks on every call and every check. */
    @Test
    void itAsksAboutTheTransactionOnEveryCall() {
        AtomicBoolean inside = new AtomicBoolean();
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), PASSING, audit, metering, inside::get);
        Request request = ask(gateway, "chat", "Hello");

        Result before = answer(gateway, request);
        inside.set(true);
        assertThatThrownBy(() -> answer(gateway, request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.checkInput(CALLER, "chat", "Hello")).isInstanceOf(IllegalStateException.class);
        inside.set(false);
        Result after = answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(before).isInstanceOf(Completed.class);
        assertThat(after).isInstanceOf(Completed.class);
        assertThat(events).containsExactly("model m", "model m");
    }

    /**
     * A-14, A-15 and blueprint 9.4: a gateway needs to know about transactions, and it calls no model without the trust
     * layer's checks or its audit.
     */
    @Test
    void aGatewayCannotBeBuiltWithoutTheTransactionCheckTheChecksOrTheAudit() {
        Map<String, RoutingGateway.Model> models = Map.of("m", model("m", DONE));
        Map<String, Route> routes = Map.of("chat", route("m"));

        assertThatThrownBy(() -> new RoutingGateway(models, routes, PASSING, audit, metering, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RoutingGateway(models, routes, null, audit, metering, OUTSIDE)).isInstanceOf(NullPointerException.class)
                .hasMessage("A gateway calls no model without the trust layer's checks (A-15)");
        assertThatThrownBy(() -> new RoutingGateway(models, routes, PASSING, null, metering, OUTSIDE)).isInstanceOf(NullPointerException.class)
                .hasMessage("A gateway calls no model without the trust layer's audit (blueprint 9.4)");
        assertThatThrownBy(() -> new RoutingGateway(models, routes, PASSING, audit, null, OUTSIDE)).isInstanceOf(NullPointerException.class)
                .hasMessage("A gateway calls no model without metering (M3-6-1)");
    }

    @Test
    void aRouteToAModelTheGatewayWasNotGivenStopsIt() {
        Map<String, RoutingGateway.Model> models = Map.of("m", model("m", DONE));

        assertThatThrownBy(() -> new RoutingGateway(models, Map.of("chat", route("m", "missing")), PASSING, audit, metering, OUTSIDE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The route of chat names the model missing, which the gateway was not given");
    }

    @Test
    void aRouteNeedsAModelAndATimeAboveZero() {
        List<Runnable> wrong = List.of(
                () -> new Route(List.of(), MINUTE, CheckMode.SENTENCE, prompts(List.of())),
                () -> new Route(null, MINUTE, CheckMode.SENTENCE, prompts(null)),
                () -> new Route(Arrays.asList("m", null), MINUTE, CheckMode.SENTENCE, prompts(Arrays.asList("m", null))),
                () -> new Route(List.of("m"), Duration.ZERO, CheckMode.SENTENCE, prompts(List.of("m"))),
                () -> new Route(List.of("m"), Duration.ofSeconds(-1), CheckMode.SENTENCE, prompts(List.of("m"))),
                () -> new Route(List.of("m"), null, CheckMode.SENTENCE, prompts(List.of("m"))));

        for (Runnable route : wrong) {
            assertThatThrownBy(route::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** M3-2: the model that answers gets the prompt its route gives that model, not another model's. */
    @Test
    void theModelGetsItsOwnPromptOfTheRoute() {
        Map<String, Prompt> prompts = Map.of("first", new Prompt("chat/first/v2", "First family."),
                "second", new Prompt("chat/second/v1", "Second family."));
        Gateway gateway = new RoutingGateway(Map.of("first", model("first", DONE), "second", model("second", DONE)),
                Map.of("chat", new Route(List.of("first", "second"), MINUTE, CheckMode.SENTENCE, prompts)), PASSING, audit, metering, OUTSIDE);

        answer(gateway, ask(gateway, "chat", "Hi"));

        assertThat(calls).singleElement().extracting(ModelPort.Request::instructions).isEqualTo("First family.");
    }

    /** M3-2: a route has one prompt for each of its models and no other, so no model is called without one. */
    @Test
    void aRouteNeedsAPromptForEachModelAndNoOther() {
        Map<String, Prompt> withNull = new HashMap<>();
        withNull.put("m", null);
        List<Runnable> wrong = List.of(
                () -> new Route(List.of("m"), MINUTE, CheckMode.SENTENCE, null),
                () -> new Route(List.of("m"), MINUTE, CheckMode.SENTENCE, Map.of()),
                () -> new Route(List.of("m"), MINUTE, CheckMode.SENTENCE, Map.of("m", PROMPT, "other", PROMPT)),
                () -> new Route(List.of("m", "n"), MINUTE, CheckMode.SENTENCE, Map.of("m", PROMPT)),
                () -> new Route(List.of("m"), MINUTE, CheckMode.SENTENCE, withNull));

        for (Runnable route : wrong) {
            assertThatThrownBy(route::run).isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("A route needs a prompt");
        }
    }

    /** M3-2: a route keeps its own copy, so changing the map it was given changes nothing. */
    @Test
    void aRouteKeepsItsOwnPrompts() {
        Map<String, Prompt> given = new HashMap<>(Map.of("m", PROMPT));
        Route route = new Route(List.of("m"), MINUTE, CheckMode.SENTENCE, given);
        given.put("m", new Prompt("chat/test/v2", "Changed."));

        assertThat(route.prompts()).containsExactly(Map.entry("m", PROMPT));
    }

    /** M3-2: a prompt is named task/family/vN and has a text. */
    @Test
    void aPromptHasAVersionedNameAndAText() {
        assertThat(new Prompt("customer_chat/fake/v12", "Answer.").id()).isEqualTo("customer_chat/fake/v12");
        for (String id : new String[] {null, "customer_chat/fake", "customer_chat/fake/v0", "customer_chat/fake/1",
                "Customer/fake/v1", "customer_chat/Fake/v1", "customer_chat/fake/v1/x", "customer_chat/fake/v12345"}) {
            assertThatThrownBy(() -> new Prompt(id, "Answer.")).hasMessage(
                    "A prompt is named task/family/vN, such as customer_chat/fake/v1, not " + id);
        }
        assertThatThrownBy(() -> new Prompt("customer_chat/fake/v1", " \n")).hasMessage("The prompt customer_chat/fake/v1 has no text");
        assertThatThrownBy(() -> new Prompt("customer_chat/fake/v1", null)).hasMessage("The prompt customer_chat/fake/v1 has no text");
    }

    /** A task cannot reach a model unchecked by leaving its checks out (A-15, M3-1). */
    @Test
    void aRouteNeedsItsChecks() {
        assertThatThrownBy(() -> new Route(List.of("m"), MINUTE, null, prompts(List.of("m"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A route needs its checks: sentence, full or input");
    }

    @Test
    void theTablesItWasGivenCannotChangeIt() {
        Map<String, RoutingGateway.Model> models = new HashMap<>(Map.of("m", model("m", DONE)));
        Map<String, Route> routes = new HashMap<>(Map.of("chat", route("m")));
        List<String> preference = new ArrayList<>(List.of("m"));
        Route route = new Route(preference, MINUTE, CheckMode.SENTENCE, prompts(preference));
        Gateway gateway = new RoutingGateway(models, routes, PASSING, audit, metering, OUTSIDE);
        Gateway second = new RoutingGateway(models, Map.of("chat", route), PASSING, audit, metering, OUTSIDE);

        models.clear();
        routes.clear();
        preference.set(0, "missing");

        assertThat(answer(gateway, ask(gateway, "chat", "Hello"))).isInstanceOf(Completed.class);
        assertThat(answer(second, ask(second, "chat", "Hello"))).isInstanceOf(Completed.class);
    }

    /**
     * M3-5-1: a call has an entry before its model is asked, which the model's call comes after, and one when it ends.
     * Both are for the caller of the question's receipt, with the message the answer is written to, the call's class, the
     * model and its prompt, and no text of what was said.
     */
    @Test
    void aCallIsAuditedBeforeTheModelIsAskedAndWhenItEnds() {
        List<Integer> entriesTheModelFound = new CopyOnWriteArrayList<>();
        Gateway gateway = new RoutingGateway(Map.of("m", new RoutingGateway.Model((request, output) -> {
            entriesTheModelFound.add(audited.size());
            output.text("a");
            return DONE;
        }, DataClass.SENSITIVE, PRICE)), Map.of("chat", route("m")), PASSING, audit, metering, OUTSIDE);
        Gateway.Caller other = new Gateway.Caller("ten_b", "app_b", "eu_b", "conv_b");
        Request request = new Request("chat", List.of(new Turn(Role.USER, "Hello there")), NEVER, DataClass.PERSONAL,
                (Checked) gateway.checkInput(other, "chat", "Hello there"), "msg_b");

        assertThat(answer(gateway, request)).isInstanceOf(Completed.class);

        assertThat(entriesTheModelFound).containsExactly(1);
        assertThat(contents).containsExactly(new Audit.Content("Hello there", null), new Audit.Content(null, "a"));
        assertThat(audited).containsExactly(
                new Audit.Entry(null, "ten_b", "app_b", "eu_b", "conv_b", "msg_b", "chat", Audit.Event.MODEL_CALLED,
                        DataClass.PERSONAL, "m", "chat/test/v1", null),
                new Audit.Entry(null, "ten_b", "app_b", "eu_b", "conv_b", "msg_b", "chat", Audit.Event.COMPLETED,
                        DataClass.PERSONAL, "m", "chat/test/v1", null));
        assertThat(audited.toString()).doesNotContain("Hello there");
    }

    /** M3-5-1: the entry at the end tells how the call ended, and one the output check stopped names the rule. */
    @Test
    void theEntryAtTheEndTellsHowTheCallEnded() {
        Gateway gateway = new RoutingGateway(Map.of(
                        "stops", model("stops", new ModelPort.Cancelled(new ModelPort.Usage(4, 1))),
                        "breaks", model("breaks", new ModelPort.Failed("the model's own words", 2, new ModelPort.Usage(4, 6))),
                        "leaks", model("leaks", DONE, "a secret")),
                Map.of("stop", route("stops"), "break", route("breaks"), "leak", route("leaks")),
                outputs((task, mode, instructions) -> stoppingAt("secret", new ArrayList<>())), audit, metering, OUTSIDE);

        answer(gateway, ask(gateway, "stop", "Hello"));
        answer(gateway, ask(gateway, "break", "Hello"));
        answer(gateway, ask(gateway, "leak", "Hello"));

        assertThat(audited).extracting(entry -> entry.task() + " " + entry.event() + " " + entry.model() + " " + entry.rule())
                .containsExactly("stop MODEL_CALLED stops null", "stop CANCELLED stops null",
                        "break MODEL_CALLED breaks null", "break FAILED breaks null",
                        "leak MODEL_CALLED leaks null", "leak OUTPUT_BLOCKED leaks test_rule");
        assertThat(audited.toString()).doesNotContain("the model's own words", "a secret");
        assertThat(contents).extracting(Audit.Content::question).containsExactly("Hello", null, "Hello", null, "Hello", null);
        assertThat(contents).extracting(Audit.Content::answer).containsExactly(null, "", null, "", null, "");
    }

    /**
     * M3-5-1: a text the input check blocks is audited by its rule, for its caller and task, with no message, class, model
     * or prompt, since nothing was stored or sent. A text that passes has no entry of its own, as the entry before its
     * call tells of it; its receipt keeps the caller, whom the answers to it are for.
     */
    @Test
    void aBlockedTextIsAuditedAndOneThatPassesIsNot() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), blocking("secret"), audit, metering, OUTSIDE);

        assertThat(((Checked) gateway.checkInput(CALLER, "chat", "Hello")).caller()).isEqualTo(CALLER);
        assertThat(audited).isEmpty();
        assertThat(gateway.checkInput(CALLER, "chat", "the secret words")).isEqualTo(new Blocked("test_rule"));

        assertThat(audited).containsExactly(new Audit.Entry(
                null, "ten_a", "app_a", "eu_a", "conv_a", null, "chat", Audit.Event.INPUT_BLOCKED, null, null, null, "test_rule"));
        assertThat(contents).containsExactly(Audit.Content.NONE);
        assertThat(calls).isEmpty();
    }

    /**
     * M3-5-3: the entry before a call is given the question as it was checked, not as the model is shown it, masked, and
     * the one at its end what the caller got of the answer, as the output check let it out: the texts the conversation
     * keeps, so that the HMAC a sensitive entry keeps of them can be checked against it. A blocked answer's is what went
     * out before the stop, and a held one's is what the end let out.
     */
    @Test
    void theAuditIsGivenTheQuestionAsCheckedAndTheAnswerAsTheCallerGotIt() {
        Gateway masked = new RoutingGateway(Map.of("m", model("m", DONE, "Call ", "0901")), Map.of("chat", route("m")),
                masking(DataClass.PERSONAL), audit, metering, OUTSIDE);
        Gateway stopping = new RoutingGateway(Map.of("m", new RoutingGateway.Model(listening("Fine. ", "secret b ", "c"),
                DataClass.SENSITIVE, PRICE)), Map.of("chat", route("m")), outputs((task, mode, instructions) -> stoppingAt("secret",
                new ArrayList<>())), audit, metering, OUTSIDE);
        Gateway held = new RoutingGateway(Map.of("m", model("m", DONE, "a", "b")), Map.of("chat", route("m")),
                outputs((task, mode, instructions) -> holding(new StringBuilder())), audit, metering, OUTSIDE);

        assertThat(answer(masked, new Request("chat", List.of(new Turn(Role.USER, "My number is 0901")), NEVER, DataClass.PERSONAL,
                (Checked) masked.checkInput(CALLER, "chat", "My number is 0901"), MESSAGE))).isInstanceOf(Completed.class);
        assertThat(answer(stopping, ask(stopping, "chat", "Tell me"))).isInstanceOf(Gateway.OutputBlocked.class);
        assertThat(answer(held, ask(held, "chat", "Whole"))).isInstanceOf(Completed.class);

        assertThat(contents).containsExactly(new Audit.Content("My number is 0901", null), new Audit.Content(null, "Call 0901"),
                new Audit.Content("Tell me", null), new Audit.Content(null, "Fine. "),
                new Audit.Content("Whole", null), new Audit.Content(null, "ab"));
        assertThat(calls.getFirst().turns().getLast().text()).isEqualTo("My number is [n]");
    }

    /**
     * M3-5-3: what a call was about never goes to the log, not even when its entry cannot be added and the log keeps the
     * entry instead; a content's own text shows its lengths alone.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void whatACallWasAboutNeverGoesToTheLog(CapturedOutput log) {
        Audit endsOutOfReach = (entry, content) -> {
            if (entry.event() != Audit.Event.MODEL_CALLED) {
                throw new IllegalStateException("The test's audit is out of reach for " + content);
            }
        };
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "Tell no one. ")), Map.of("chat", route("m")), PASSING,
                endsOutOfReach, metering, OUTSIDE);

        assertThat(answer(gateway, ask(gateway, "chat", "My secret question"))).isInstanceOf(Completed.class);

        assertThat(errorLines(log)).singleElement().asString().startsWith("The audit could not add Entry[");
        assertThat(log).contains("The test's audit is out of reach for Content[question none, answer 13 characters]")
                .doesNotContain("Tell no one", "My secret question");
        assertThat(new Audit.Content("secret words", "more")).hasToString("Content[question 12 characters, answer 4 characters]");
    }

    /** M3-5-1: the input check needs the caller the audit names, and a caller names all four of its IDs. */
    @Test
    void theInputCheckNeedsItsCaller() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), audit, metering, OUTSIDE);

        assertThatThrownBy(() -> gateway.checkInput(null, "chat", "Hello"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The input check needs the caller, whom the audit names");
        assertThat(events).isEmpty();
        for (Runnable incomplete : List.<Runnable>of(() -> new Gateway.Caller(null, "app_a", "eu_a", "conv_a"),
                () -> new Gateway.Caller("ten_a", null, "eu_a", "conv_a"), () -> new Gateway.Caller("ten_a", "app_a", null, "conv_a"),
                () -> new Gateway.Caller("ten_a", "app_a", "eu_a", null))) {
            assertThatThrownBy(incomplete::run).isInstanceOf(IllegalArgumentException.class).hasMessage(
                    "A caller of the gateway names its tenant, application, end user and conversation, for the audit");
        }
    }

    /** M3-5-1: a request names the message its answer is written to, or it is a mistake: no entry, and no model. */
    @Test
    void aRequestWithoutItsMessageIsAMistake() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), PASSING, audit, metering, OUTSIDE);
        Request request = ask(gateway, "chat", "Hello");

        assertThatThrownBy(() -> answer(gateway, new Request("chat", request.turns(), NEVER, DataClass.PUBLIC, request.question(), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A request names the message its answer is written to, for the audit");
        assertThat(audited).isEmpty();
        assertThat(calls).isEmpty();
    }

    /**
     * M3-5-1: when the entry before a call cannot be added, the call fails before its model is asked, so no model call
     * goes without its entry.
     */
    @Test
    void noModelIsAskedWithoutItsEntry() {
        Audit outOfReach = (entry, content) -> {
            throw new IllegalStateException("The test's audit is out of reach");
        };
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), PASSING, outOfReach, metering, OUTSIDE);
        Request request = ask(gateway, "chat", "Hello");

        assertThatThrownBy(() -> answer(gateway, request))
                .isInstanceOf(IllegalStateException.class).hasMessage("The test's audit is out of reach");
        assertThat(calls).isEmpty();
        assertThat(events).isEmpty();
    }

    /**
     * M3-5-1: an entry that tells what happened already, a blocked text or the end of a call, changes nothing of what
     * happened when it cannot be added: an error in the log keeps it, with all of its fields, and the result stands.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anEntryOfWhatHappenedThatCannotBeAddedGoesToTheLog(CapturedOutput log) {
        Audit firstOnly = (entry, content) -> {
            if (entry.event() != Audit.Event.MODEL_CALLED) {
                throw new IllegalStateException("The test's audit is out of reach");
            }
            audited.add(entry);
        };
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), blocking("secret"),
                firstOnly, metering, OUTSIDE);

        assertThat(gateway.checkInput(CALLER, "chat", "the secret words")).isEqualTo(new Blocked("test_rule"));
        assertThat(answer(gateway, ask(gateway, "chat", "Hello"))).isEqualTo(new Completed(new Usage(5, 2)));

        assertThat(events).containsExactly("model m", "caller a");
        assertThat(audited).extracting(Audit.Entry::event).containsExactly(Audit.Event.MODEL_CALLED);
        assertThat(errorLines(log)).containsExactly(
                "The audit could not add Entry[requestId=null, tenantId=ten_a, applicationId=app_a, endUserId=eu_a,"
                        + " conversationId=conv_a, messageId=null, task=chat, event=INPUT_BLOCKED, dataClass=null, model=null,"
                        + " prompt=null, rule=test_rule], so this line keeps it",
                "The audit could not add Entry[requestId=null, tenantId=ten_a, applicationId=app_a, endUserId=eu_a,"
                        + " conversationId=conv_a, messageId=msg_a, task=chat, event=COMPLETED, dataClass=PUBLIC, model=m,"
                        + " prompt=chat/test/v1, rule=null], so this line keeps it");
        assertThat(log).contains("The test's audit is out of reach").doesNotContain("the secret words");
    }

    /** M3-5-1: a call that goes wrong on its way, such as one whose output throws, keeps its first entry alone, as a crash does. */
    @Test
    void aCallThatGoesWrongOnItsWayKeepsItsFirstEntryAlone() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), PASSING, audit, metering, OUTSIDE);
        Request request = ask(gateway, "chat", "Hello");

        assertThatThrownBy(() -> gateway.answer(request, piece -> {
            throw new IllegalStateException("The caller went wrong");
        })).hasMessage("The caller went wrong");
        assertThat(audited).extracting(Audit.Entry::event).containsExactly(Audit.Event.MODEL_CALLED);
    }

    /**
     * M3-5-1: each entry carries the request ID of the calling thread's log context, the one its request's log lines carry,
     * and none outside a request.
     */
    @Test
    void theEntriesCarryTheRequestIdOfTheLogContext() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), blocking("secret"), audit, metering, OUTSIDE);
        MDC.put(LogContext.REQUEST_ID, "req_1");
        try {
            gateway.checkInput(CALLER, "chat", "the secret words");
            answer(gateway, ask(gateway, "chat", "Hello"));
        } finally {
            MDC.remove(LogContext.REQUEST_ID);
        }
        answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(audited).extracting(Audit.Entry::requestId).containsExactly("req_1", "req_1", "req_1", null, null);
    }

    /**
     * M3-6-1: each call is metered once at its end, however it ended, since the model was paid for it: the tokens it
     * reports and what they cost by the model's price, exactly, for the caller of the question's receipt, the message and
     * the request of the log context.
     */
    @Test
    void eachCallIsMeteredAtItsEndHoweverItEnded() {
        Gateway gateway = new RoutingGateway(Map.of(
                        "done", model("done", new ModelPort.Completed(new ModelPort.Usage(1234, 567)), "a"),
                        "stops", model("stops", new ModelPort.Cancelled(new ModelPort.Usage(4, 1))),
                        "breaks", model("breaks", new ModelPort.Failed("the model's own words", 2, new ModelPort.Usage(4, 6))),
                        "leaks", model("leaks", DONE, "a secret")),
                Map.of("done", route("done"), "stop", route("stops"), "break", route("breaks"), "leak", route("leaks")),
                outputs((task, mode, instructions) -> stoppingAt("secret", new ArrayList<>())), audit, metering, OUTSIDE);
        MDC.put(LogContext.REQUEST_ID, "req_9");
        try {
            answer(gateway, ask(gateway, "done", "Hello"));
            answer(gateway, ask(gateway, "stop", "Hello"));
        } finally {
            MDC.remove(LogContext.REQUEST_ID);
        }
        answer(gateway, ask(gateway, "break", "Hello"));
        answer(gateway, ask(gateway, "leak", "Hello"));

        assertThat(metered).containsExactly(
                new Metering.Use("req_9", "ten_a", "app_a", MESSAGE, "done", "done", 1234, 567, new BigDecimal("0.012207")),
                new Metering.Use("req_9", "ten_a", "app_a", MESSAGE, "stop", "stops", 4, 1, new BigDecimal("0.000027")),
                new Metering.Use(null, "ten_a", "app_a", MESSAGE, "break", "breaks", 4, 6, new BigDecimal("0.000102")),
                new Metering.Use(null, "ten_a", "app_a", MESSAGE, "leak", "leaks", 5, 2, new BigDecimal("0.000045")));
    }

    /**
     * M3-6-1: what reached no model is not metered: a blocked text, a call from inside a transaction, one above the
     * ceiling or without this gateway's receipt; and a call that goes wrong on its way, as a crash does, since its usage
     * never came back.
     */
    @Test
    void whatReachedNoModelOrWentWrongOnItsWayIsNotMetered() {
        AtomicBoolean inside = new AtomicBoolean();
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DataClass.INTERNAL, DONE, "a")), Map.of("chat", route("m")),
                blocking("secret"), audit, metering, inside::get);
        Request hello = ask(gateway, "chat", "Hello");

        gateway.checkInput(CALLER, "chat", "the secret words");
        assertThatThrownBy(() -> answer(gateway, new Request("chat", hello.turns(), NEVER, DataClass.SENSITIVE, hello.question(), MESSAGE)))
                .isInstanceOf(IllegalArgumentException.class);
        inside.set(true);
        assertThatThrownBy(() -> answer(gateway, hello)).isInstanceOf(IllegalStateException.class);
        inside.set(false);
        assertThatThrownBy(() -> gateway.answer(hello, piece -> {
            throw new IllegalStateException("The caller went wrong");
        })).hasMessage("The caller went wrong");

        assertThat(metered).isEmpty();
        assertThat(answer(gateway, hello)).isInstanceOf(Completed.class);
        assertThat(metered).hasSize(1);
    }

    /**
     * M3-6-1: a use that cannot be recorded changes nothing of what happened: an error in the log keeps it, with all of
     * its fields, and the result stands.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aUseThatCannotBeRecordedGoesToTheLog(CapturedOutput log) {
        Metering outOfReach = use -> {
            throw new IllegalStateException("The test's metering is out of reach");
        };
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), PASSING, audit,
                outOfReach, OUTSIDE);

        assertThat(answer(gateway, ask(gateway, "chat", "Hello"))).isEqualTo(new Completed(new Usage(5, 2)));

        assertThat(errorLines(log)).containsExactly("Metering could not record Use[requestId=null, tenantId=ten_a, applicationId=app_a,"
                + " messageId=msg_a, task=chat, model=m, inputTokens=5, outputTokens=2, cost=0.000045], so this line keeps it");
        assertThat(audited).extracting(Audit.Entry::event).containsExactly(Audit.Event.MODEL_CALLED, Audit.Event.COMPLETED);
    }

    /** The messages of the log's ERROR lines, in order. */
    private static List<String> errorLines(CapturedOutput log) {
        return log.toString().lines().filter(line -> line.contains(" ERROR "))
                .map(line -> line.substring(line.indexOf(" -- ") + 4)).toList();
    }

    /** Checks that block a text with this word by test_rule, pass every other text, and let every piece out as it is. */
    private static Checks blocking(String word) {
        return new Checks() {
            @Override
            public Input input(String task, String text) {
                return text.contains(word) ? new Checks.Blocked("test_rule") : new Checks.Passed(DataClass.PUBLIC);
            }

            @Override
            public String masked(String text) {
                return text;
            }

            @Override
            public Output output(String task, CheckMode mode, String instructions) {
                return AS_IT_IS;
            }
        };
    }

    /** How a test opens the output check of each answer. */
    @FunctionalInterface
    private interface Outputs {

        Checks.Output open(String task, CheckMode mode, String instructions);
    }

    /** Checks that pass every text, find no personal data, mask nothing, and open each answer's output check so. */
    private static Checks outputs(Outputs outputs) {
        return new Checks() {
            @Override
            public Input input(String task, String text) {
                return new Passed(DataClass.PUBLIC);
            }

            @Override
            public String masked(String text) {
                return text;
            }

            @Override
            public Output output(String task, CheckMode mode, String instructions) {
                return outputs.open(task, mode, instructions);
            }
        };
    }

    /** An output check that holds everything and lets it all out at the end. */
    private static Checks.Output holding(StringBuilder held) {
        return new Checks.Output() {
            @Override
            public Checks.Verdict piece(String piece) {
                held.append(piece);
                return new Checks.Release("");
            }

            @Override
            public Checks.Verdict end() {
                return new Checks.Release(held.toString());
            }
        };
    }

    /** An output check that notes each piece, lets it out, and stops the answer at the first piece with this word. */
    private static Checks.Output stoppingAt(String word, List<String> checked) {
        return new Checks.Output() {
            @Override
            public Checks.Verdict piece(String piece) {
                checked.add(piece);
                return piece.contains(word) ? new Checks.Stop("test_rule") : new Checks.Release(piece);
            }

            @Override
            public Checks.Verdict end() {
                return new Checks.Release("");
            }
        };
    }

    /** A model that sends its pieces, but stops as soon as it is told to, as a real one does. */
    private ModelPort listening(String... pieces) {
        return (request, output) -> {
            for (String piece : pieces) {
                if (request.cancellation().requested()) {
                    events.add("model stopped");
                    return new ModelPort.Cancelled(new ModelPort.Usage(5, 2));
                }
                output.text(piece);
            }
            return DONE;
        };
    }

    /** Checks that pass every text, find this class in it, and mask every run of digits as [n]. */
    private static Checks masking(DataClass found) {
        return new Checks() {
            @Override
            public Input input(String task, String text) {
                return new Passed(found);
            }

            @Override
            public String masked(String text) {
                return text.replaceAll("[0-9]+", "[n]");
            }

            @Override
            public Output output(String task, CheckMode mode, String instructions) {
                return AS_IT_IS;
            }
        };
    }

    /**
     * Checks that note what they see, pass every text, find no personal data, and upper-case each piece, so the caller's
     * pieces show they passed the check.
     */
    private Checks noting() {
        return new Checks() {
            @Override
            public Input input(String task, String text) {
                events.add("input " + task + " " + text);
                return new Passed(DataClass.PUBLIC);
            }

            @Override
            public String masked(String text) {
                return text;
            }

            @Override
            public Output output(String task, CheckMode mode, String instructions) {
                return new Output() {
                    @Override
                    public Verdict piece(String piece) {
                        events.add("output " + task + " " + piece);
                        return new Release(piece.toUpperCase(Locale.ROOT));
                    }

                    @Override
                    public Verdict end() {
                        return new Release("");
                    }
                };
            }
        };
    }
}

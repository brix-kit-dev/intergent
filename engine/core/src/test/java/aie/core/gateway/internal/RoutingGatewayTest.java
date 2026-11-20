package aie.core.gateway.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.Checks;
import aie.core.trust.api.DataClass;
import aie.ports.ModelPort;
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
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class RoutingGatewayTest {

    private static final Gateway.Cancellation NEVER = () -> false;
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
        }, max);
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
        return (Checked) gateway.checkInput(task, text);
    }

    /** One turn of the user, of public data, checked by the gateway first. */
    private static Request ask(Gateway gateway, String task, String said) {
        return new Request(task, List.of(new Turn(Role.USER, said)), NEVER, DataClass.PUBLIC, checked(gateway, task, said));
    }

    private Result answer(Gateway gateway, Request request) {
        return gateway.answer(request, piece -> events.add("caller " + piece));
    }

    @Test
    void itAnswersWithTheFirstModelOfTheTasksRoute() {
        Gateway gateway = new RoutingGateway(
                Map.of("first", model("first", DONE, "a", "b"), "second", model("second", DONE, "x")),
                Map.of("chat", route("first", "second")), PASSING, OUTSIDE);

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
                Map.of("chat", route("internal", "sensitive", "public")), PASSING, OUTSIDE);
        Checked hello = checked(gateway, "chat", "Hello");

        for (DataClass dataClass : DataClass.values()) {
            answer(gateway, new Request("chat", List.of(new Turn(Role.USER, "Hello")), NEVER, dataClass, hello));
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
                        "care", route("public", "sensitive", "internal")), PASSING, OUTSIDE);

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
                Map.of("chat", route("low")), noting(), OUTSIDE);
        Turn hello = new Turn(Role.USER, "Hello");
        Checked checked = checked(gateway, "chat", "Hello");

        for (DataClass above : List.of(DataClass.PERSONAL, DataClass.SENSITIVE)) {
            assertThatThrownBy(() -> answer(gateway, new Request("chat", List.of(hello), NEVER, above, checked)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("No model of the task chat takes " + above.wireName()
                            + " data, only up to internal, so the caller refuses such a call before anything goes");
        }
        assertThat(events).containsExactly("input chat Hello");
        assertThat(answer(gateway, new Request("chat", List.of(hello), NEVER, DataClass.INTERNAL, checked)))
                .isInstanceOf(Completed.class);
        assertThat(events).containsExactly("input chat Hello", "model low", "output chat a", "caller A");
    }

    @Test
    void aModelOfTheGatewayNeedsItsAdapterAndItsHighestClass() {
        ModelPort port = (request, output) -> DONE;

        assertThatThrownBy(() -> new RoutingGateway.Model(null, DataClass.PUBLIC)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A model of the gateway needs its adapter and the highest data class it takes");
        assertThatThrownBy(() -> new RoutingGateway.Model(port, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A model of the gateway needs its adapter and the highest data class it takes");
    }

    @Test
    void eachTaskGoesToItsOwnRoute() {
        Gateway gateway = new RoutingGateway(
                Map.of("one", model("one", DONE), "two", model("two", DONE)),
                Map.of("chat", route("one"), "translate", route("two")), PASSING, OUTSIDE);

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
                PASSING, OUTSIDE);

        answer(gateway, ask(gateway, "quick", "Hello"));
        answer(gateway, ask(gateway, "slow", "Hello"));

        assertThat(calls).extracting(ModelPort.Request::timeout).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(90));
    }

    @Test
    void theModelGetsTheConversationAndTheCancel() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), PASSING, OUTSIDE);
        AtomicBoolean cancel = new AtomicBoolean();

        answer(gateway, new Request("chat", List.of(new Turn(Role.USER, "Hi"), new Turn(Role.ASSISTANT, "Hello"),
                new Turn(Role.USER, "Bye")), cancel::get, DataClass.PUBLIC, checked(gateway, "chat", "Bye")));

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
                Map.of("stop", route("stops"), "break", route("breaks")), PASSING, OUTSIDE);

        assertThat(answer(gateway, ask(gateway, "stop", "Hello"))).isEqualTo(new Cancelled(new Usage(4, 1)));
        assertThat(answer(gateway, ask(gateway, "break", "Hello"))).isEqualTo(new Failed(2, new Usage(4, 6)));
    }

    /** The model's reason is for the logs; the result has no place for it, so it cannot reach the user. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailureIsLoggedWithTheModelsReason(CapturedOutput output) {
        Gateway gateway = new RoutingGateway(
                Map.of("m", model("m", new ModelPort.Failed("the vendor is out of quota", 1, new ModelPort.Usage(5, 3)), "a")),
                Map.of("chat", route("m")), PASSING, OUTSIDE);

        Result result = answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(output).contains("The model m failed the task chat with the prompt chat/test/v1 after 1 piece(s): the vendor is out of quota");
        assertThat(result).hasToString("Failed[pieces=1, usage=Usage[inputTokens=5, outputTokens=3]]");
    }

    /** A task without a route is a mistake, whether its text is checked or answered, and no check or model sees it. */
    @Test
    void aTaskWithoutARouteIsAMistake() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), OUTSIDE);
        Checked hello = checked(gateway, "chat", "Hello");
        events.clear();

        assertThatThrownBy(() -> gateway.checkInput("translate", "Hello"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No route answers the task translate");
        assertThatThrownBy(() -> gateway.checkInput(null, "Hello")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> answer(gateway, new Request("translate", List.of(new Turn(Role.USER, "Hello")), NEVER,
                DataClass.PUBLIC, hello))).isInstanceOf(IllegalArgumentException.class).hasMessage("No route answers the task translate");
        assertThatThrownBy(() -> answer(gateway, new Request(null, List.of(new Turn(Role.USER, "Hello")), NEVER,
                DataClass.PUBLIC, hello))).isInstanceOf(IllegalArgumentException.class);
        assertThat(events).isEmpty();
        assertThat(calls).isEmpty();
    }

    @Test
    void aRequestThatIsNotWellFormedIsAMistake() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), OUTSIDE);
        Checked checked = checked(gateway, "chat", "Hello");
        events.clear();
        Turn hello = new Turn(Role.USER, "Hello");
        List<Request> wrong = List.of(
                new Request("chat", List.of(), NEVER, DataClass.PUBLIC, checked),
                new Request("chat", null, NEVER, DataClass.PUBLIC, checked),
                new Request("chat", List.of(hello, new Turn(Role.ASSISTANT, "Hi")), NEVER, DataClass.PUBLIC, checked),
                new Request("chat", List.of(new Turn(Role.USER, null)), NEVER, DataClass.PUBLIC, checked),
                new Request("chat", List.of(new Turn(null, "Hello")), NEVER, DataClass.PUBLIC, checked),
                new Request("chat", Arrays.asList(null, hello), NEVER, DataClass.PUBLIC, checked),
                new Request("chat", List.of(hello), null, DataClass.PUBLIC, checked),
                new Request("chat", List.of(hello), NEVER, null, checked));

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
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a", "b")), Map.of("chat", route("m")), noting(), OUTSIDE);

        answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(events).containsExactly("input chat Hello", "model m", "output chat a", "caller A", "output chat b", "caller B");
    }

    /** M3-4-1: a text that passes comes back as the gateway's receipt for that task and that very text. */
    @Test
    void aTextThatPassesComesBackAsItsReceipt() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), OUTSIDE);

        Gateway.InputCheck check = gateway.checkInput("chat", "Hello there");

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
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), blocking, OUTSIDE);

        assertThat(gateway.checkInput("chat", "the secret words")).isEqualTo(new Blocked("test_rule"));

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
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), failing, OUTSIDE);

        assertThatThrownBy(() -> gateway.checkInput("chat", "Hello")).hasMessage("not this one");
        assertThat(calls).isEmpty();
    }

    @Test
    void theInputCheckNeedsAText() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), OUTSIDE);

        assertThatThrownBy(() -> gateway.checkInput("chat", null))
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
        Gateway gateway = new RoutingGateway(models, routes, noting(), OUTSIDE);
        Gateway other = new RoutingGateway(models, routes, noting(), OUTSIDE);
        Checked made = new Checked() {
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
            assertThatThrownBy(() -> answer(gateway, new Request("chat", turns, NEVER, DataClass.PUBLIC, receipt)))
                    .as(String.valueOf(receipt)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A question reaches a model only with this gateway's receipt of its input check, for the same task"
                            + " and the same text (A-15)");
        }
        assertThat(events).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(answer(gateway, new Request("chat", turns, NEVER, DataClass.PUBLIC, checked(gateway, "chat", "Bye"))))
                .isInstanceOf(Completed.class);
    }

    /** Blueprint 9.1: the same text is checked once, so its receipt serves every call for it, each without a check. */
    @Test
    void aReceiptServesEveryCallForItsText() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting(), OUTSIDE);
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
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), masking(DataClass.PUBLIC),
                OUTSIDE);
        List<Turn> turns = List.of(new Turn(Role.USER, "Call 0901"), new Turn(Role.ASSISTANT, "On 0901?"),
                new Turn(Role.USER, "Yes, 0901"));

        answer(gateway, new Request("chat", turns, NEVER, DataClass.PUBLIC, checked(gateway, "chat", "Yes, 0901")));
        answer(gateway, new Request("chat", turns, NEVER, DataClass.PUBLIC, checked(gateway, "chat", "Yes, 0901")));

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
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), masking(DataClass.PERSONAL),
                OUTSIDE);
        Checked checked = checked(gateway, "chat", "My number is 0901");
        List<Turn> turns = List.of(new Turn(Role.USER, "My number is 0901"));

        assertThat(checked.dataClass()).isEqualTo(DataClass.PERSONAL);
        for (DataClass below : List.of(DataClass.PUBLIC, DataClass.INTERNAL)) {
            assertThatThrownBy(() -> answer(gateway, new Request("chat", turns, NEVER, below, checked)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The question holds personal data, so a call of " + below.wireName()
                            + " is too low for it; the caller raises the call first (M3-4-2)");
        }
        assertThat(calls).isEmpty();
        assertThat(answer(gateway, new Request("chat", turns, NEVER, DataClass.PERSONAL, checked))).isInstanceOf(Completed.class);
        assertThat(answer(gateway, new Request("chat", turns, NEVER, DataClass.SENSITIVE, checked))).isInstanceOf(Completed.class);
        assertThat(calls).extracting(call -> call.turns().getLast().text()).containsExactly("My number is [n]", "My number is [n]");
    }

    /** A receipt carries the class its check found: public for a text without personal data. */
    @Test
    void aReceiptCarriesTheClassItsCheckFound() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), PASSING, OUTSIDE);

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
                recording, OUTSIDE);

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
                outputs((task, mode, instructions) -> holding(new StringBuilder())), OUTSIDE);

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
        Gateway gateway = new RoutingGateway(Map.of("m", new RoutingGateway.Model(listening("a", "secret b", "c"), DataClass.SENSITIVE)),
                Map.of("chat", route("m")), outputs((task, mode, instructions) -> stoppingAt("secret", checked)), OUTSIDE);

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
                outputs((task, mode, instructions) -> stoppingAt("secret", checked)), OUTSIDE);

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
                outputs((task, mode, instructions) -> holding(new StringBuilder())), OUTSIDE);

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
                outputs((task, mode, instructions) -> stopsAtTheEnd), OUTSIDE);

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
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), noting(), inside::get);
        Request request = ask(gateway, "chat", "Hello");
        Request unrouted = new Request("translate", request.turns(), NEVER, DataClass.PUBLIC, request.question());
        events.clear();
        inside.set(true);

        assertThatThrownBy(() -> answer(gateway, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No model is called inside a database transaction (A-14), so end it first");
        assertThatThrownBy(() -> answer(gateway, unrouted)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.checkInput("chat", "Hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No text is checked inside a database transaction (A-14), so end it first");
        assertThatThrownBy(() -> gateway.checkInput("translate", "Hello")).isInstanceOf(IllegalStateException.class);
        assertThat(events).isEmpty();
        assertThat(calls).isEmpty();
    }

    /** One gateway serves threads in and out of transactions, so it asks on every call and every check. */
    @Test
    void itAsksAboutTheTransactionOnEveryCall() {
        AtomicBoolean inside = new AtomicBoolean();
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), PASSING, inside::get);
        Request request = ask(gateway, "chat", "Hello");

        Result before = answer(gateway, request);
        inside.set(true);
        assertThatThrownBy(() -> answer(gateway, request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateway.checkInput("chat", "Hello")).isInstanceOf(IllegalStateException.class);
        inside.set(false);
        Result after = answer(gateway, ask(gateway, "chat", "Hello"));

        assertThat(before).isInstanceOf(Completed.class);
        assertThat(after).isInstanceOf(Completed.class);
        assertThat(events).containsExactly("model m", "model m");
    }

    /** A-14 and A-15: a gateway needs to know about transactions, and it calls no model without the trust layer's checks. */
    @Test
    void aGatewayCannotBeBuiltWithoutTheTransactionCheckOrTheChecks() {
        Map<String, RoutingGateway.Model> models = Map.of("m", model("m", DONE));
        Map<String, Route> routes = Map.of("chat", route("m"));

        assertThatThrownBy(() -> new RoutingGateway(models, routes, PASSING, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RoutingGateway(models, routes, null, OUTSIDE)).isInstanceOf(NullPointerException.class)
                .hasMessage("A gateway calls no model without the trust layer's checks (A-15)");
    }

    @Test
    void aRouteToAModelTheGatewayWasNotGivenStopsIt() {
        Map<String, RoutingGateway.Model> models = Map.of("m", model("m", DONE));

        assertThatThrownBy(() -> new RoutingGateway(models, Map.of("chat", route("m", "missing")), PASSING, OUTSIDE))
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
                Map.of("chat", new Route(List.of("first", "second"), MINUTE, CheckMode.SENTENCE, prompts)), PASSING, OUTSIDE);

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
        Gateway gateway = new RoutingGateway(models, routes, PASSING, OUTSIDE);
        Gateway second = new RoutingGateway(models, Map.of("chat", route), PASSING, OUTSIDE);

        models.clear();
        routes.clear();
        preference.set(0, "missing");

        assertThat(answer(gateway, ask(gateway, "chat", "Hello"))).isInstanceOf(Completed.class);
        assertThat(answer(second, ask(second, "chat", "Hello"))).isInstanceOf(Completed.class);
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

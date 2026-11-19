package aie.core.gateway.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.gateway.api.Gateway;
import aie.core.gateway.api.Gateway.Cancelled;
import aie.core.gateway.api.Gateway.Completed;
import aie.core.gateway.api.Gateway.Failed;
import aie.core.gateway.api.Gateway.Request;
import aie.core.gateway.api.Gateway.Result;
import aie.core.gateway.api.Gateway.Role;
import aie.core.gateway.api.Gateway.Turn;
import aie.core.gateway.api.Gateway.Usage;
import aie.core.trust.api.CheckMode;
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

    /** What happened, in order: the checks, the models' calls and the pieces the caller got. */
    private final List<String> events = new CopyOnWriteArrayList<>();
    /** The calls the models got. */
    private final List<ModelPort.Request> calls = new CopyOnWriteArrayList<>();

    /** A model that notes its call, sends the pieces and ends with the result. */
    private ModelPort model(String name, ModelPort.Result result, String... pieces) {
        return (request, output) -> {
            events.add("model " + name);
            calls.add(request);
            for (String piece : pieces) {
                output.text(piece);
            }
            return result;
        };
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

    private static Request ask(String task, String said) {
        return new Request(task, List.of(new Turn(Role.USER, said)), NEVER);
    }

    private Result answer(Gateway gateway, Request request) {
        return gateway.answer(request, piece -> events.add("caller " + piece));
    }

    @Test
    void itAnswersWithTheFirstModelOfTheTasksRoute() {
        Gateway gateway = new RoutingGateway(
                Map.of("first", model("first", DONE, "a", "b"), "second", model("second", DONE, "x")),
                Map.of("chat", route("first", "second")), OUTSIDE);

        Result result = answer(gateway, ask("chat", "Hello"));

        assertThat(events).containsExactly("model first", "caller a", "caller b");
        assertThat(calls).singleElement().extracting(ModelPort.Request::model).isEqualTo("first");
        assertThat(result).isEqualTo(new Completed(new Usage(5, 2)));
    }

    @Test
    void eachTaskGoesToItsOwnRoute() {
        Gateway gateway = new RoutingGateway(
                Map.of("one", model("one", DONE), "two", model("two", DONE)),
                Map.of("chat", route("one"), "translate", route("two")), OUTSIDE);

        answer(gateway, ask("translate", "Hello"));
        answer(gateway, ask("chat", "Hello"));

        assertThat(events).containsExactly("model two", "model one");
    }

    /** A-9: no call goes out without a time limit, and the limit is the route's. */
    @Test
    void everyCallCarriesTheTimeOfItsRoute() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of(
                "quick", new Route(List.of("m"), Duration.ofSeconds(3), CheckMode.SENTENCE, prompts(List.of("m"))),
                "slow", new Route(List.of("m"), Duration.ofSeconds(90), CheckMode.SENTENCE, prompts(List.of("m")))), OUTSIDE);

        answer(gateway, ask("quick", "Hello"));
        answer(gateway, ask("slow", "Hello"));

        assertThat(calls).extracting(ModelPort.Request::timeout).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(90));
    }

    @Test
    void theModelGetsTheConversationAndTheCancel() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), OUTSIDE);
        AtomicBoolean cancel = new AtomicBoolean();

        answer(gateway, new Request("chat", List.of(
                new Turn(Role.USER, "Hi"), new Turn(Role.ASSISTANT, "Hello"), new Turn(Role.USER, "Bye")), cancel::get));

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
                Map.of("stop", route("stops"), "break", route("breaks")), OUTSIDE);

        assertThat(answer(gateway, ask("stop", "Hello"))).isEqualTo(new Cancelled(new Usage(4, 1)));
        assertThat(answer(gateway, ask("break", "Hello"))).isEqualTo(new Failed(2, new Usage(4, 6)));
    }

    /** The model's reason is for the logs; the result has no place for it, so it cannot reach the user. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailureIsLoggedWithTheModelsReason(CapturedOutput output) {
        Gateway gateway = new RoutingGateway(
                Map.of("m", model("m", new ModelPort.Failed("the vendor is out of quota", 1, new ModelPort.Usage(5, 3)), "a")),
                Map.of("chat", route("m")), OUTSIDE);

        Result result = answer(gateway, ask("chat", "Hello"));

        assertThat(output).contains("The model m failed the task chat with the prompt chat/test/v1 after 1 piece(s): the vendor is out of quota");
        assertThat(result).hasToString("Failed[pieces=1, usage=Usage[inputTokens=5, outputTokens=3]]");
    }

    @Test
    void aTaskWithoutARouteIsAMistake() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), OUTSIDE);

        assertThatThrownBy(() -> answer(gateway, ask("translate", "Hello")))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("No route answers the task translate");
        assertThatThrownBy(() -> answer(gateway, ask(null, "Hello"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void aRequestThatIsNotWellFormedIsAMistake() {
        Checks noting = noting();
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), noting, OUTSIDE);
        Turn hello = new Turn(Role.USER, "Hello");
        List<Request> wrong = List.of(
                new Request("chat", List.of(), NEVER),
                new Request("chat", null, NEVER),
                new Request("chat", List.of(hello, new Turn(Role.ASSISTANT, "Hi")), NEVER),
                new Request("chat", List.of(new Turn(Role.USER, null)), NEVER),
                new Request("chat", List.of(new Turn(null, "Hello")), NEVER),
                new Request("chat", Arrays.asList(null, hello), NEVER),
                new Request("chat", List.of(hello), null));

        for (Request request : wrong) {
            assertThatThrownBy(() -> answer(gateway, request)).as(request.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(events).isEmpty();
    }

    /** Blueprint 8.2: the input check comes before the model, and the caller sees only what the output check passed. */
    @Test
    void theChecksStandBetweenTheCallerAndTheModel() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a", "b")), Map.of("chat", route("m")), noting(), OUTSIDE);

        answer(gateway, ask("chat", "Hello"));

        assertThat(events).containsExactly("input chat", "model m", "output chat a", "caller A", "output chat b", "caller B");
    }

    @Test
    void aRequestTheInputCheckStopsReachesNoModel() {
        Checks refusing = new Checks() {
            @Override
            public void input(Request request) {
                throw new IllegalStateException("not this one");
            }

            @Override
            public String output(String task, String piece) {
                return piece;
            }
        };
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), refusing, OUTSIDE);

        assertThatThrownBy(() -> answer(gateway, ask("chat", "Hello"))).hasMessage("not this one");
        assertThat(events).isEmpty();
    }

    /**
     * A-14: from inside a transaction nothing is checked, chosen or called. Even a task without a route gets this answer,
     * which shows that the gateway asks before anything else.
     */
    @Test
    void aCallFromInsideATransactionIsRefusedBeforeAnything() {
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE, "a")), Map.of("chat", route("m")), noting(), () -> true);

        assertThatThrownBy(() -> answer(gateway, ask("chat", "Hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No model is called inside a database transaction (A-14), so end it first");
        assertThatThrownBy(() -> answer(gateway, ask("translate", "Hello"))).isInstanceOf(IllegalStateException.class);
        assertThat(events).isEmpty();
        assertThat(calls).isEmpty();
    }

    /** One gateway serves threads in and out of transactions, so it asks on every call. */
    @Test
    void itAsksAboutTheTransactionOnEveryCall() {
        AtomicBoolean inside = new AtomicBoolean();
        Gateway gateway = new RoutingGateway(Map.of("m", model("m", DONE)), Map.of("chat", route("m")), inside::get);

        Result before = answer(gateway, ask("chat", "Hello"));
        inside.set(true);
        assertThatThrownBy(() -> answer(gateway, ask("chat", "Hello"))).isInstanceOf(IllegalStateException.class);
        inside.set(false);
        Result after = answer(gateway, ask("chat", "Hello"));

        assertThat(before).isInstanceOf(Completed.class);
        assertThat(after).isInstanceOf(Completed.class);
        assertThat(events).containsExactly("model m", "model m");
    }

    @Test
    void aGatewayCannotBeBuiltWithoutTheTransactionCheck() {
        Map<String, ModelPort> models = Map.of("m", model("m", DONE));
        Map<String, Route> routes = Map.of("chat", route("m"));

        assertThatThrownBy(() -> new RoutingGateway(models, routes, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RoutingGateway(models, routes, Checks.NONE, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aRouteToAModelTheGatewayWasNotGivenStopsIt() {
        Map<String, ModelPort> models = Map.of("m", model("m", DONE));

        assertThatThrownBy(() -> new RoutingGateway(models, Map.of("chat", route("m", "missing")), OUTSIDE))
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
                Map.of("chat", new Route(List.of("first", "second"), MINUTE, CheckMode.SENTENCE, prompts)), OUTSIDE);

        answer(gateway, ask("chat", "Hi"));

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
        Map<String, ModelPort> models = new HashMap<>(Map.of("m", model("m", DONE)));
        Map<String, Route> routes = new HashMap<>(Map.of("chat", route("m")));
        List<String> preference = new ArrayList<>(List.of("m"));
        Route route = new Route(preference, MINUTE, CheckMode.SENTENCE, prompts(preference));
        Gateway gateway = new RoutingGateway(models, routes, OUTSIDE);
        Gateway second = new RoutingGateway(models, Map.of("chat", route), OUTSIDE);

        models.clear();
        routes.clear();
        preference.set(0, "missing");

        assertThat(answer(gateway, ask("chat", "Hello"))).isInstanceOf(Completed.class);
        assertThat(answer(second, ask("chat", "Hello"))).isInstanceOf(Completed.class);
    }

    /** Checks that note what they see and upper-case each piece, so the caller's pieces show they passed the check. */
    private Checks noting() {
        return new Checks() {
            @Override
            public void input(Request request) {
                events.add("input " + request.task());
            }

            @Override
            public String output(String task, String piece) {
                events.add("output " + task + " " + piece);
                return piece.toUpperCase(Locale.ROOT);
            }
        };
    }
}

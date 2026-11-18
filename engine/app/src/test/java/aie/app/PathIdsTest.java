package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.Problem;
import aie.modules.conversation.api.Messages;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.ConfigurableApplicationContext;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Point 36: one rule for every ID in a path. A value the contract's schema does not allow is refused with 400 and the
 * rule, before anything is looked up, by every operation that takes the ID. This reads the operations and the schemas of
 * their path IDs from the contract and calls them on the real web stack with stand-ins, so an operation added to the
 * contract later is checked here without a change to this test, as CallersTest does for the callers. A path parameter
 * that is not an ID of the one shape the rule knows fails here, so a new kind of parameter needs a rule first.
 */
class PathIdsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path CONTRACT = Path.of("../../contracts/openapi.yaml");
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}]+)}");
    /** The one shape of ID the rule knows: a prefix of lower-case letters ending in _, then letters and digits. */
    private static final Pattern ID_SCHEMA = Pattern.compile("\\^([a-z]+_)\\[0-9A-Za-z\\]\\+\\$");
    /** A body each operation takes, so that its checks pass; any other gets an empty object. */
    private static final Map<String, String> BODIES = Map.of("createMessage", "{\"text\":\"Hi\"}");

    private static final TestApi.StandInKeys KEYS = new TestApi.StandInKeys();
    private static final TestApi.StandInEndUsers END_USERS = new TestApi.StandInEndUsers();
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    private static ConfigurableApplicationContext context;
    private static TestApi.StandInMessages messages;
    private static int port;

    /** An ID in a path of the contract: its parameter's name, the prefix of its schema, and its longest length. */
    record PathId(String name, String prefix, int maxLength) {

        /** What the engine says when a value breaks the rule. */
        String rule() {
            return name + " must be " + prefix + " followed by 1 to " + (maxLength - prefix.length()) + " letters or digits";
        }

        String longest() {
            return prefix + "x".repeat(maxLength - prefix.length());
        }

        /**
         * Values the rule refuses: nothing after the prefix, the prefix in capitals, a character that is not a letter or
         * a digit, a line break at the end, and one character more than the longest.
         */
        List<String> malformed() {
            return List.of(prefix, prefix.toUpperCase(Locale.ROOT) + "1", prefix + "a-b", prefix + "1%0A", longest() + "x");
        }
    }

    /** An operation of the contract with IDs in its path. */
    record Operation(String method, String path, String operationId, List<PathId> ids) {

        @Override
        public String toString() {
            return operationId + ", " + method + " " + path;
        }
    }

    /** One value for one ID of one operation, with a well-formed value for each other ID of its path. */
    record Call(Operation operation, PathId id, String value) {

        @Override
        public String toString() {
            return operation.operationId() + " with " + id.name() + "=" + value;
        }
    }

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, KEYS, END_USERS, CONVERSATIONS, ANSWERS, "--server.port=0");
        messages = (TestApi.StandInMessages) context.getBean(Messages.class);
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    /**
     * Each test sees only the calls it made itself, whatever ran before it. No ID here names anything, so an ask finds no
     * conversation, as it would on the database.
     */
    @BeforeEach
    void forgetEarlierCalls() {
        KEYS.calls.clear();
        END_USERS.calls.clear();
        CONVERSATIONS.calls.clear();
        ANSWERS.calls.clear();
        ANSWERS.outcome = TestApi.StandInAnswers.Outcome.MISSING;
        messages.leaseLooks.clear();
        messages.pageReads.clear();
    }

    /** Every operation of the contract with a parameter in its path, with what its schema allows for each of them. */
    static List<Operation> operations() throws IOException {
        Map<String, Object> contract = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(CONTRACT));
        List<Operation> operations = new ArrayList<>();
        map(contract.get("paths")).forEach((path, item) -> {
            List<String> names = new ArrayList<>();
            Matcher parameter = PATH_PARAMETER.matcher(path);
            while (parameter.find()) {
                names.add(parameter.group(1));
            }
            map(item).forEach((method, value) -> {
                if (METHODS.contains(method) && !names.isEmpty()) {
                    Map<String, Object> operation = map(value);
                    List<Object> parameters = new ArrayList<>(list(map(item).get("parameters")));
                    parameters.addAll(list(operation.get("parameters")));
                    List<PathId> ids = names.stream().map(name -> pathId(contract, path, name, parameters)).toList();
                    operations.add(new Operation(method.toUpperCase(Locale.ROOT), path, (String) operation.get("operationId"), ids));
                }
            });
        });
        return operations;
    }

    /** The parameter of the path with this name, as an ID of the one shape the rule knows. */
    private static PathId pathId(Map<String, Object> contract, String path, String name, List<Object> parameters) {
        for (Object node : parameters) {
            Map<String, Object> parameter = resolved(contract, node);
            if ("path".equals(parameter.get("in")) && name.equals(parameter.get("name"))) {
                Map<String, Object> schema = resolved(contract, parameter.get("schema"));
                Matcher shape = ID_SCHEMA.matcher(String.valueOf(schema.get("pattern")));
                if (!"string".equals(schema.get("type")) || !shape.matches() || !(schema.get("maxLength") instanceof Integer max)) {
                    throw new IllegalStateException("The path parameter " + name + " of " + path
                            + " is not an ID of the shape point 36's rule knows: " + schema);
                }
                return new PathId(name, shape.group(1), max);
            }
        }
        throw new IllegalStateException("The contract does not describe the path parameter " + name + " of " + path);
    }

    /** The node itself, or the one its $ref names in the contract. */
    private static Map<String, Object> resolved(Map<String, Object> contract, Object node) {
        Map<String, Object> map = map(node);
        if (!(map.get("$ref") instanceof String ref)) {
            return map;
        }
        Object target = contract;
        for (String step : ref.substring("#/".length()).split("/")) {
            target = map(target).get(step);
        }
        return resolved(contract, target);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        return node == null ? Map.of() : (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object node) {
        return node == null ? List.of() : (List<Object>) node;
    }

    /** Every malformed value of every ID of every operation. */
    static Stream<Call> malformedIds() throws IOException {
        return operations().stream().flatMap(operation -> operation.ids().stream()
                .flatMap(id -> id.malformed().stream().map(value -> new Call(operation, id, value))));
    }

    /** The longest value of every ID of every operation. */
    static Stream<Call> longestIds() throws IOException {
        return operations().stream().flatMap(operation -> operation.ids().stream()
                .map(id -> new Call(operation, id, id.longest())));
    }

    /** The operation with the call's value for its ID, a well-formed value for each other ID, and a server key. */
    private static HttpResponse<String> send(Call call) throws Exception {
        Matcher parameter = PATH_PARAMETER.matcher(call.operation().path());
        StringBuilder path = new StringBuilder();
        while (parameter.find()) {
            String name = parameter.group(1);
            String value = name.equals(call.id().name()) ? call.value() : call.operation().ids().stream()
                    .filter(id -> id.name().equals(name)).findFirst().orElseThrow().prefix() + "1";
            parameter.appendReplacement(path, Matcher.quoteReplacement(value));
        }
        parameter.appendTail(path);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", TestApi.AUTHORIZATION);
        String method = call.operation().method();
        if (Set.of("POST", "PUT", "PATCH").contains(method)) {
            request.header("Content-Type", "application/json").method(method,
                    BodyPublishers.ofString(BODIES.getOrDefault(call.operation().operationId(), "{}")));
        } else {
            request.method(method, BodyPublishers.noBody());
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** So that a contract this test cannot read fails here, rather than leaving the tests below with nothing to call. */
    @Test
    void theContractNamesThePathIdsOfItsOperations() throws IOException {
        List<Operation> operations = operations();

        assertThat(operations).extracting(Operation::operationId).contains("deleteApiKey", "getConversation",
                "createMessage", "listMessages", "getMessage", "streamMessageEvents", "cancelMessage");
        assertThat(operations).flatExtracting(Operation::ids).contains(new PathId("key_id", "key_", 64),
                new PathId("conversation_id", "conv_", 64), new PathId("message_id", "msg_", 64));
    }

    @ParameterizedTest
    @MethodSource("malformedIds")
    void aMalformedIdIsRefusedBeforeAnythingIsLookedUp(Call call) throws Exception {
        HttpResponse<String> response = send(call);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo("invalid_request");
        assertThat(problem.getDetail()).isEqualTo(call.id().rule());
        assertThat(KEYS.calls).isEmpty();
        assertThat(END_USERS.calls).isEmpty();
        assertThat(CONVERSATIONS.calls).isEmpty();
        assertThat(ANSWERS.calls).isEmpty();
        assertThat(messages.leaseLooks).isEmpty();
        assertThat(messages.pageReads).isEmpty();
    }

    /** The longest ID the schema allows passes the rule and is looked up; none of these exists, so each is missing. */
    @ParameterizedTest
    @MethodSource("longestIds")
    void theLongestIdIsLookedUp(Call call) throws Exception {
        HttpResponse<String> response = send(call);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JSON.readValue(response.body(), Problem.class).getCode()).isEqualTo("not_found");
    }
}

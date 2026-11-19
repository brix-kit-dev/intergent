package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.SessionTokens;
import aie.http.api.model.Problem;
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
import java.util.stream.Collectors;
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
 * Point 37: no string in a request body holds a NUL. PostgreSQL cannot store one, and nothing the contract describes has
 * a use for one, so a NUL in any string of any JSON request body is refused with 400 before anything is made. This reads
 * the operations, their bodies and every string in them from the contract, and calls each operation on the real web
 * stack with stand-ins, once for each string with a NUL at its start, in its middle and at its end, so a field added to
 * the contract later is checked here without a change to this test. Each operation needs a body here that passes every
 * check and holds every string of its schema; without one, this test fails and says what to add.
 *
 * <p>The one exception is the anonymous session's site_key. It is a credential, and a credential that is wrong in any
 * way reads as unknown, 401, as an API key does.
 */
class RequestTextTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path CONTRACT = Path.of("../../contracts/openapi.yaml");
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}]+)}");
    /** A well-formed value for each path parameter of the contract. */
    private static final Map<String, String> PATH_VALUES = Map.of("conversation_id", "conv_1", "message_id", "msg_1", "key_id", "key_1");
    /** A page the stand-in site key lists, for the operation that takes no credential but a site key. */
    private static final String PAGE = "https://shop.example.com";
    /** A body for each operation that passes every check and holds every string of the operation's schema. */
    private static final Map<String, String> BODIES = Map.of(
            "createSession", "{\"external_user_id\":\"u1\",\"identity_level\":\"verified\",\"scopes\":[\"faq\"],"
                    + "\"context\":{\"order_id\":\"A-1001\"},\"locale\":\"ja-JP\"}",
            "createAnonymousSession", "{\"site_key\":\"" + TestApi.SITE_KEY + "\",\"locale\":\"ja-JP\","
                    + "\"previous_token\":\"" + TestApi.SESSION_TOKENS.issue(new SessionTokens.Session("ten_test", "app_test",
                            "eu_1", SessionTokens.IdentityLevel.ANONYMOUS, List.of(), Map.of(), null)).token() + "\"}",
            "createApiKey", "{\"name\":\"ci\",\"expires_at\":\"2099-01-01T00:00:00Z\"}",
            "createConversation", "{\"external_user_id\":\"host-user-1\",\"locale\":\"ja-JP\"}",
            "createMessage", "{\"text\":\"Hi\",\"locale\":\"ja-JP\",\"data_class\":\"internal\"}");
    /** The strings that are credentials, as operation and path. Wrong in any way, a credential reads as unknown. */
    private static final Set<String> CREDENTIALS = Set.of("createAnonymousSession: site_key");
    /** Stands in a path for every value of a map, such as each value of a session's context. */
    private static final String EACH = "*";

    private static final TestApi.StandInKeys KEYS = new TestApi.StandInKeys();
    private static final TestApi.StandInEndUsers END_USERS = new TestApi.StandInEndUsers();
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    private static ConfigurableApplicationContext context;
    private static int port;

    /** An operation of the contract with a JSON body, whether it takes a credential, and the strings of its body. */
    record Operation(String method, String path, String operationId, boolean credential, List<List<Object>> strings) {

        @Override
        public String toString() {
            return operationId;
        }
    }

    /** Where the NUL goes in a string: a check that looks at one place only, or trims the ends, misses the others. */
    enum Where {
        START, MIDDLE, END;

        String put(String value) {
            return switch (this) {
                case START -> "\0" + value;
                case MIDDLE -> value.substring(0, 1) + "\0" + value.substring(1);
                case END -> value + "\0";
            };
        }
    }

    /**
     * One string of one operation's body, by its path in the body: names, item 0 of a list, or each value of a map; and
     * where the NUL goes in it.
     */
    record Text(Operation operation, List<Object> path, Where where) {

        /** The operation and the path, such as createSession: context.* */
        String field() {
            return operation.operationId() + ": " + path.stream().map(step -> step instanceof Integer index
                    ? "[" + index + "]" : String.valueOf(step)).collect(Collectors.joining(".")).replace(".[", "[");
        }

        String name() {
            return field() + ", NUL at the " + where.name().toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return name();
        }
    }

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, KEYS, END_USERS, CONVERSATIONS, ANSWERS, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    /** Each test sees only the calls it made itself, whatever ran before it. */
    @BeforeEach
    void forgetEarlierCalls() {
        KEYS.calls.clear();
        END_USERS.calls.clear();
        CONVERSATIONS.calls.clear();
        ANSWERS.calls.clear();
        ANSWERS.outcome = TestApi.StandInAnswers.Outcome.COMPLETED;
    }

    /** Every operation of the contract with a JSON request body, with the strings its schema holds. */
    static List<Operation> operations() throws IOException {
        Map<String, Object> contract = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(CONTRACT));
        boolean anyCredential = !list(contract.get("security")).isEmpty();
        List<Operation> operations = new ArrayList<>();
        map(contract.get("paths")).forEach((path, item) -> map(item).forEach((method, value) -> {
            if (METHODS.contains(method)) {
                Map<String, Object> operation = map(value);
                Object schema = map(map(map(operation.get("requestBody")).get("content")).get("application/json")).get("schema");
                if (schema != null) {
                    List<List<Object>> strings = new ArrayList<>();
                    collect(contract, schema, List.of(), strings);
                    boolean credential = operation.containsKey("security") ? !list(operation.get("security")).isEmpty() : anyCredential;
                    operations.add(new Operation(method.toUpperCase(Locale.ROOT), path, (String) operation.get("operationId"),
                            credential, strings));
                }
            }
        }));
        return operations;
    }

    /**
     * The paths of the strings in this schema. A schema this test does not know how to walk fails here, so that a new
     * kind of body is looked at before it is trusted.
     */
    private static void collect(Map<String, Object> contract, Object node, List<Object> path, List<List<Object>> strings) {
        Map<String, Object> schema = resolved(contract, node);
        if (schema.containsKey("oneOf") || schema.containsKey("anyOf") || schema.containsKey("not")) {
            throw new IllegalStateException("This test cannot walk " + schema.keySet() + " at " + path + " yet");
        }
        for (Object part : list(schema.get("allOf"))) {
            collect(contract, part, path, strings);
        }
        Object type = schema.get("type");
        if ("string".equals(type)) {
            strings.add(path);
        } else if ("array".equals(type)) {
            collect(contract, schema.get("items"), append(path, 0), strings);
        } else if ("object".equals(type)) {
            map(schema.get("properties")).forEach((name, property) -> collect(contract, property, append(path, name), strings));
            if (schema.get("additionalProperties") instanceof Map<?, ?> values) {
                collect(contract, values, append(path, EACH), strings);
            }
        } else if (type != null && !Set.of("integer", "number", "boolean").contains(type)) {
            throw new IllegalStateException("This test cannot walk the type " + type + " at " + path + " yet");
        }
    }

    private static List<Object> append(List<Object> path, Object step) {
        List<Object> longer = new ArrayList<>(path);
        longer.add(step);
        return List.copyOf(longer);
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

    /** Every string of every request body, with the NUL at each place in turn. */
    static Stream<Text> texts() throws IOException {
        return operations().stream().flatMap(operation -> operation.strings().stream()
                .flatMap(path -> Stream.of(Where.values()).map(where -> new Text(operation, path, where))));
    }

    /** The operation's body here; an operation without one fails the test, which names it. */
    private static String body(Operation operation) {
        String body = BODIES.get(operation.operationId());
        if (body == null) {
            throw new IllegalStateException("Give " + operation.operationId()
                    + " a body in RequestTextTest.BODIES that passes every check and holds every string of its schema");
        }
        return body;
    }

    /** The operation's body with a NUL in the string at this path, or in each value where it says EACH. */
    private static String withNul(Text text) {
        Object body = JSON.readValue(body(text.operation()), Object.class);
        return JSON.writeValueAsString(withNul(body, text, 0));
    }

    @SuppressWarnings("unchecked")
    private static Object withNul(Object node, Text text, int at) {
        if (at == text.path().size()) {
            if (!(node instanceof String value) || value.length() < 2) {
                throw new IllegalStateException("The body for " + text.field() + " needs a string of 2 characters or more there");
            }
            return text.where().put(value);
        }
        Object step = text.path().get(at);
        if (EACH.equals(step) && node instanceof Map<?, ?> values && !values.isEmpty()) {
            ((Map<String, Object>) values).replaceAll((key, value) -> withNul(value, text, at + 1));
            return values;
        }
        if (step instanceof Integer index && node instanceof List<?> items && items.size() > index) {
            ((List<Object>) items).set(index, withNul(items.get(index), text, at + 1));
            return items;
        }
        if (step instanceof String name && node instanceof Map<?, ?> fields && fields.containsKey(name)) {
            ((Map<String, Object>) fields).put(name, withNul(fields.get(name), text, at + 1));
            return fields;
        }
        throw new IllegalStateException("The body for " + text.operation().operationId() + " leaves out " + text.field()
                + "; give it a value there, so that this test can put a NUL in it");
    }

    /** The operation with this body: a server key, or for an operation without a credential the page's origin. */
    private static HttpResponse<String> send(Operation operation, String body) throws Exception {
        Matcher parameter = PATH_PARAMETER.matcher(operation.path());
        StringBuilder path = new StringBuilder();
        while (parameter.find()) {
            String value = PATH_VALUES.get(parameter.group(1));
            if (value == null) {
                throw new IllegalStateException("This test has no value for the path parameter " + parameter.group(1));
            }
            parameter.appendReplacement(path, value);
        }
        parameter.appendTail(path);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(operation.method(), BodyPublishers.ofString(body));
        if (operation.credential()) {
            request.header("Authorization", TestApi.AUTHORIZATION);
        } else {
            request.header("Origin", PAGE);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertNothingMade() {
        assertThat(KEYS.calls).as("no key is made").isEmpty();
        assertThat(END_USERS.calls).as("no end user is made").isEmpty();
        assertThat(CONVERSATIONS.calls).as("no conversation is started").isEmpty();
        assertThat(ANSWERS.calls).as("nothing is asked").isEmpty();
    }

    /** So that a contract this test cannot read fails here, rather than leaving the tests below with nothing to call. */
    @Test
    void theContractNamesTheStringsOfItsBodies() throws IOException {
        assertThat(texts().map(Text::field).distinct()).contains("createSession: external_user_id", "createSession: scopes[0]",
                "createSession: context.*", "createAnonymousSession: site_key", "createApiKey: name", "createApiKey: expires_at",
                "createConversation: external_user_id", "createMessage: text", "createMessage: locale");
        assertThat(texts().map(Text::field).distinct().filter(CREDENTIALS::contains)).containsExactlyInAnyOrderElementsOf(CREDENTIALS);
    }

    /** The body here passes every check, so a refusal below comes from its NUL alone. */
    @ParameterizedTest
    @MethodSource("operations")
    void eachBodyHereIsTaken(Operation operation) throws Exception {
        HttpResponse<String> response = send(operation, body(operation));

        assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    }

    @ParameterizedTest
    @MethodSource("texts")
    void aNulInAnyStringIsRefusedBeforeAnythingIsMade(Text text) throws Exception {
        HttpResponse<String> response = send(text.operation(), withNul(text));

        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        if (CREDENTIALS.contains(text.field())) {
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(problem.getCode()).isEqualTo("unauthorized");
        } else {
            assertThat(response.statusCode()).as(problem.getDetail()).isEqualTo(400);
            assertThat(problem.getCode()).isEqualTo("invalid_request");
        }
        assertNothingMade();
    }
}

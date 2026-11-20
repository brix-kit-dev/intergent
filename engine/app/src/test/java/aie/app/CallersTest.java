package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.trust.api.DataClass;
import aie.http.api.model.Problem;
import aie.modules.conversation.api.Answers;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
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
 * S-10 at run time. Every operation of the contract names its callers in {@code x-callers}, and one that does not name
 * the browser is for server keys only (blueprint 6.3); the guard's contract checks keep the contract itself in order.
 * This reads every operation from the contract and calls it on the real web stack with session tokens, so an operation
 * added to the contract is checked here without a change to this test. Every browser operation takes a valid token, and
 * every one that names a conversation finds another end user's as missing (blueprint 6.3), so an operation added later,
 * such as reading messages in M2-13, must keep a browser in its own conversations or fail here. A browser's operation
 * answers its CORS preflight, and a page may read its answers to a token and to failed credentials, not to a server key
 * (M2-12-4); a server's operation answers no preflight. The anonymous session takes no credential, so its site key check
 * is SiteKeyFilter's, and it is not called here.
 */
class CallersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path CONTRACT = Path.of("../../contracts/openapi.yaml");
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    /** A well-formed value for each path parameter of the contract. */
    private static final Map<String, String> PATH_VALUES = Map.of("conversation_id", "conv_1", "message_id", "msg_1", "key_id", "key_1");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}]+)}");
    /** An origin a page on a host's site may have. */
    private static final String PAGE = "https://shop.example.com";
    /** A body each operation takes, so that its checks pass; any other gets an empty object. */
    private static final Map<String, String> BODIES = Map.of("createMessage", "{\"text\":\"Hi\"}");

    private static final TestApi.StandInKeys KEYS = new TestApi.StandInKeys();
    private static final TestApi.StandInEndUsers END_USERS = new TestApi.StandInEndUsers();
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    private static ConfigurableApplicationContext context;
    private static int port;

    /** An operation of the contract that takes a credential, and the callers it names. */
    record Operation(String method, String path, String operationId, List<Object> callers) {

        boolean forBrowsers() {
            return callers.contains("browser");
        }

        @Override
        public String toString() {
            return operationId + ", " + method + " " + path;
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

    /** Every operation of the contract with its own credentials or the contract's, except one that takes none. */
    static List<Operation> operations() throws IOException {
        Map<String, Object> contract = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(CONTRACT));
        Object anyOperation = contract.get("security");
        List<Operation> operations = new ArrayList<>();
        map(contract.get("paths")).forEach((path, item) -> map(item).forEach((method, value) -> {
            if (METHODS.contains(method)) {
                Map<String, Object> operation = map(value);
                if (!list(operation.containsKey("security") ? operation.get("security") : anyOperation).isEmpty()) {
                    operations.add(new Operation(method.toUpperCase(Locale.ROOT), path, (String) operation.get("operationId"),
                            list(operation.get("x-callers"))));
                }
            }
        }));
        return operations;
    }

    static Stream<Operation> serverOperations() throws IOException {
        return operations().stream().filter(operation -> !operation.forBrowsers());
    }

    static Stream<Operation> browserOperations() throws IOException {
        return operations().stream().filter(Operation::forBrowsers);
    }

    /** The browser operations whose path names a conversation. */
    static Stream<Operation> conversationOperations() throws IOException {
        return browserOperations().filter(operation -> operation.path().contains("{conversation_id}"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node) {
        return node == null ? Map.of() : (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object node) {
        return node == null ? List.of() : (List<Object>) node;
    }

    /** The operation's path with a well-formed value for each parameter, called with this Authorization. */
    private static HttpResponse<String> call(Operation operation, String authorization) throws Exception {
        return call(operation, authorization, Map.of());
    }

    /** The same, from a page of this origin, as a browser sends it. */
    private static HttpResponse<String> fromPage(Operation operation, String authorization, String origin) throws Exception {
        return call(operation, authorization, Map.of("origin", origin));
    }

    /** The operation's CORS preflight from the page, asking for its method. */
    private static HttpResponse<String> preflight(Operation operation) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path(operation, Map.of())))
                .header("Origin", PAGE).header("Access-Control-Request-Method", operation.method())
                .method("OPTIONS", BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The same, with these values for some of the path's parameters, and an Origin when the values name one. */
    private static HttpResponse<String> call(Operation operation, String authorization, Map<String, String> values)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path(operation, values)));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (values.containsKey("origin")) {
            request.header("Origin", values.get("origin"));
        }
        if (Set.of("POST", "PUT", "PATCH").contains(operation.method())) {
            request.header("Content-Type", "application/json").method(operation.method(),
                    BodyPublishers.ofString(BODIES.getOrDefault(operation.operationId(), "{}")));
        } else {
            request.method(operation.method(), BodyPublishers.noBody());
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The operation's path with these values for its parameters, or a well-formed one for each other. */
    private static String path(Operation operation, Map<String, String> values) {
        Matcher parameter = PATH_PARAMETER.matcher(operation.path());
        StringBuilder path = new StringBuilder();
        while (parameter.find()) {
            String value = values.getOrDefault(parameter.group(1), PATH_VALUES.get(parameter.group(1)));
            if (value == null) {
                throw new IllegalStateException("This test has no value for the path parameter " + parameter.group(1));
            }
            parameter.appendReplacement(path, value);
        }
        parameter.appendTail(path);
        return path.toString();
    }

    /** What lets the page read an answer (M2-12-4): its own origin back, the headers it may read, and Vary. */
    private static void assertForThePage(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).containsExactly(PAGE);
        assertThat(response.headers().allValues("Access-Control-Expose-Headers")).containsExactly("Request-Id, Retry-After");
        assertThat(response.headers().allValues("Vary")).contains("Origin");
        assertThat(response.headers().allValues("Access-Control-Allow-Credentials")).isEmpty();
    }

    private static void assertNotForAPage(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Access-Control-Allow-Origin")).isEmpty();
        assertThat(response.headers().allValues("Access-Control-Expose-Headers")).isEmpty();
    }

    private static Problem problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo(code);
        return problem;
    }

    private static void assertServerKeyNeeded(HttpResponse<String> response) {
        assertThat(problem(response, 403, "forbidden").getDetail()).isEqualTo("This operation takes a server key, not a session token");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer error=\"insufficient_scope\"");
    }

    /** So that a contract this test cannot read fails here, rather than leaving the tests below with nothing to call. */
    @Test
    void theContractNamesTheCallersOfEveryOperation() throws IOException {
        assertThat(operations()).allSatisfy(operation -> {
            assertThat(operation.operationId()).isNotBlank();
            assertThat(operation.callers()).isNotEmpty().isSubsetOf("server", "browser");
        });
        assertThat(serverOperations().map(Operation::operationId)).contains("createSession", "createApiKey", "deleteApiKey");
        assertThat(browserOperations().map(Operation::operationId))
                .contains("createConversation", "getConversation", "createMessage", "streamMessageEvents", "cancelMessage");
        assertThat(conversationOperations()).hasSizeGreaterThanOrEqualTo(6);
    }

    /** S-10: a browser's token never calls an operation for servers, such as one that makes tokens or keys. */
    @ParameterizedTest
    @MethodSource("serverOperations")
    void aServerOperationRefusesASessionToken(Operation operation) throws Exception {
        assertServerKeyNeeded(call(operation, "Bearer " + TestApi.sessionToken("eu_1")));
    }

    /** A browser operation lets a valid token through to the operation itself, whatever it then answers. */
    @ParameterizedTest
    @MethodSource("browserOperations")
    void everyBrowserOperationTakesASessionToken(Operation operation) throws Exception {
        assertThat(call(operation, "Bearer " + TestApi.sessionToken("eu_1")).statusCode()).isNotIn(401, 403);
    }

    /**
     * Blueprint 6.3: a browser's token reaches only its own end user's conversations. Another end user's conversation of
     * the same application, with a real answer in it, is missing to every operation that names it, as an unknown one is,
     * and nothing is asked, kept or cancelled for it.
     */
    @ParameterizedTest
    @MethodSource("conversationOperations")
    void anotherEndUsersConversationIsMissingToEveryBrowserOperation(Operation operation) throws Exception {
        String conversation = CONVERSATIONS.start(TestApi.OWNER, "eu_owner", null).conversationId();
        String answer = ((Answers.Start.Started) ANSWERS.ask(TestApi.OWNER, conversation, "Hi", DataClass.PUBLIC)).asked().answer().messageId();
        ANSWERS.calls.clear();
        String stranger = "Bearer " + TestApi.sessionToken("eu_stranger");

        HttpResponse<String> other = call(operation, stranger, Map.of("conversation_id", conversation, "message_id", answer));
        HttpResponse<String> unknown = call(operation, stranger, Map.of("conversation_id", "conv_nothing", "message_id", answer));

        problem(other, 404, "not_found");
        assertThat(other.body()).isEqualTo(unknown.body());
        assertThat(ANSWERS.calls).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("operations")
    void everyOperationRefusesAnExpiredToken(Operation operation) throws Exception {
        assertThat(problem(call(operation, "Bearer " + TestApi.expiredSessionToken()), 401, "unauthorized").getDetail())
                .isEqualTo("The API key or session token is not valid");
    }

    /** M2-12-4: every browser operation answers its preflight from any page, for its own method and the token. */
    @ParameterizedTest
    @MethodSource("browserOperations")
    void everyBrowserOperationAnswersItsPreflight(Operation operation) throws Exception {
        HttpResponse<String> answer = preflight(operation);

        assertThat(answer.statusCode()).isEqualTo(204);
        assertThat(answer.headers().firstValue("Access-Control-Allow-Origin")).contains(PAGE);
        assertThat(answer.headers().firstValue("Access-Control-Allow-Methods").orElseThrow().split(", ")).contains(operation.method());
        assertThat(answer.headers().firstValue("Access-Control-Allow-Headers").orElseThrow()).startsWith("Authorization, ");
    }

    /** A server's operation is never called from a page, so it answers no preflight: the key check refuses it. */
    @ParameterizedTest
    @MethodSource("serverOperations")
    void noServerOperationAnswersAPreflight(Operation operation) throws Exception {
        HttpResponse<String> answer = preflight(operation);

        assertThat(answer.statusCode()).isEqualTo(401);
        assertNotForAPage(answer);
    }

    /** The page reads every answer to its token, whatever the operation then answers. */
    @ParameterizedTest
    @MethodSource("browserOperations")
    void aPageReadsTheAnswerToItsToken(Operation operation) throws Exception {
        assertForThePage(fromPage(operation, "Bearer " + TestApi.sessionToken("eu_1"), PAGE));
    }

    /** An expired token, none, or a bad key: the page reads why, and so learns to fetch a new token. */
    @ParameterizedTest
    @MethodSource("browserOperations")
    void aPageReadsWhyItsCredentialFailed(Operation operation) throws Exception {
        for (String authorization : Arrays.asList("Bearer " + TestApi.expiredSessionToken(), null,
                "Bearer aie_test_dev_" + "9876543210".repeat(4))) {
            HttpResponse<String> response = fromPage(operation, authorization, PAGE);
            assertThat(response.statusCode()).isEqualTo(401);
            assertForThePage(response);
        }
    }

    /** M2-12 decision 3: an answer to a server key is not for a page, so a key put in a page fails there at once (S-5). */
    @ParameterizedTest
    @MethodSource("browserOperations")
    void aServerKeysAnswerIsNotForAPage(Operation operation) throws Exception {
        assertNotForAPage(fromPage(operation, TestApi.AUTHORIZATION, PAGE));
    }

    /** No page has an origin such as this, so none may read the answer; the request itself is the token's to make. */
    @ParameterizedTest
    @MethodSource("browserOperations")
    void anOriginNoPageCouldHaveReadsNothing(Operation operation) throws Exception {
        assertNotForAPage(fromPage(operation, "Bearer " + TestApi.sessionToken("eu_1"), "http://shop.example.com"));
    }

    /** A server's operation refuses a token without letting any page read why. */
    @ParameterizedTest
    @MethodSource("serverOperations")
    void aServerOperationsRefusalIsNotForAPage(Operation operation) throws Exception {
        HttpResponse<String> response = fromPage(operation, "Bearer " + TestApi.sessionToken("eu_1"), PAGE);

        assertThat(response.statusCode()).isEqualTo(403);
        assertNotForAPage(response);
    }

    /** The token is refused before the operation runs, so nothing is made, looked up, asked or cancelled. */
    @Test
    void aRefusedTokenRunsNothing() throws Exception {
        for (Operation operation : serverOperations().toList()) {
            call(operation, "Bearer " + TestApi.sessionToken("eu_1"));
        }

        assertThat(KEYS.calls).isEmpty();
        assertThat(END_USERS.calls).isEmpty();
        assertThat(CONVERSATIONS.calls).isEmpty();
        assertThat(ANSWERS.calls).isEmpty();
    }
}

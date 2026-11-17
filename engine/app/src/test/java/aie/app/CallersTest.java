package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
 * added to the contract is checked here without a change to this test. No operation takes a token yet, so the browser
 * operations refuse a valid one too, until M2-12-2 and M2-12-3. The anonymous session takes no credential, so its site
 * key check is SiteKeyFilter's, and it is not called here.
 */
class CallersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path CONTRACT = Path.of("../../contracts/openapi.yaml");
    private static final Set<String> METHODS = Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
    /** A well-formed value for each path parameter of the contract. */
    private static final Map<String, String> PATH_VALUES = Map.of("conversation_id", "conv_1", "message_id", "msg_1", "key_id", "key_1");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}]+)}");

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
                .header("Authorization", authorization);
        if (Set.of("POST", "PUT", "PATCH").contains(operation.method())) {
            request.header("Content-Type", "application/json").method(operation.method(), BodyPublishers.ofString("{}"));
        } else {
            request.method(operation.method(), BodyPublishers.noBody());
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
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
    }

    /** S-10: a browser's token never calls an operation for servers, such as one that makes tokens or keys. */
    @ParameterizedTest
    @MethodSource("serverOperations")
    void aServerOperationRefusesASessionToken(Operation operation) throws Exception {
        assertServerKeyNeeded(call(operation, "Bearer " + TestApi.sessionToken("eu_1")));
    }

    /** M2-12-2 and M2-12-3 open these to tokens, each with the check that a token reaches only its own conversations. */
    @ParameterizedTest
    @MethodSource("browserOperations")
    void noBrowserOperationTakesASessionTokenYet(Operation operation) throws Exception {
        assertServerKeyNeeded(call(operation, "Bearer " + TestApi.sessionToken("eu_1")));
    }

    @ParameterizedTest
    @MethodSource("operations")
    void everyOperationRefusesAnExpiredToken(Operation operation) throws Exception {
        assertThat(problem(call(operation, "Bearer " + TestApi.expiredSessionToken()), 401, "unauthorized").getDetail())
                .isEqualTo("The API key or session token is not valid");
    }

    /** The token is refused before the operation runs, so nothing is made, looked up, asked or cancelled. */
    @Test
    void aRefusedTokenRunsNothing() throws Exception {
        for (Operation operation : operations()) {
            call(operation, "Bearer " + TestApi.sessionToken("eu_1"));
        }

        assertThat(KEYS.calls).isEmpty();
        assertThat(END_USERS.calls).isEmpty();
        assertThat(CONVERSATIONS.calls).isEmpty();
        assertThat(ANSWERS.calls).isEmpty();
    }
}

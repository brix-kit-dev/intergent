package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.Problem;
import aie.ports.DatabaseException;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Blueprint 6.5 on the real web stack, with a stand-in for the key lookup. {@code ApiRoleTest} runs the same filter
 * on the real lookup and database.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/v1/nothing-here";
    private static final String NO_KEY = "Send an API key as Authorization: Bearer";
    private static final String BAD_KEY = "The API key is not valid";
    private static final String BAD_CREDENTIAL = "The API key or session token is not valid";
    private static final String SERVER_KEY_NEEDED = "This operation takes a server key, not a session token";
    private static final String NOT_ON_BEHALF = "A session token acts only for its own tenant, so On-Behalf-Of takes a server key";
    /** Well formed and unknown to the stand-in. */
    private static final String UNKNOWN_KEY = "aie_test_dev_" + "9876543210".repeat(4);

    private static volatile boolean lookupFails;
    private static final AtomicInteger LOOKUPS = new AtomicInteger();
    private static ConfigurableApplicationContext context;
    private static int port;

    /** What a /v1 handler learns about the caller. */
    private record Caller(String keyId, String tenantId, String applicationId) {
    }

    @BeforeAll
    static void start() {
        context = TestApi.start(key -> {
            LOOKUPS.incrementAndGet();
            if (lookupFails) {
                throw new DatabaseException("The transaction failed", null);
            }
            return TestApi.KEY.equals(key) ? Optional.of(TestApi.OWNER) : Optional.empty();
        }, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static HttpRequest.Builder withKey(String path) {
        return request(path).header("Authorization", TestApi.AUTHORIZATION);
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Problem problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo(code);
        return problem;
    }

    private static void assertNoKey(HttpResponse<String> response) {
        assertThat(problem(response, 401, "unauthorized").getDetail()).isEqualTo(NO_KEY);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
    }

    private static void assertBadKey(HttpResponse<String> response) {
        assertBadCredential(response, BAD_KEY);
    }

    private static void assertBadCredential(HttpResponse<String> response, String detail) {
        assertThat(problem(response, 401, "unauthorized").getDetail()).isEqualTo(detail);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer error=\"invalid_token\"");
    }

    /** RFC 6750 3.1: a valid token that may not make the request gets 403 and says so in the challenge. */
    private static void assertForbiddenToken(HttpResponse<String> response, String detail) {
        assertThat(problem(response, 403, "forbidden").getDetail()).isEqualTo(detail);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer error=\"insufficient_scope\"");
    }

    /** Each starts with aie_, as every API key does, so each is looked up as a key. */
    static List<String> badKeys() {
        return List.of(
                "Bearer " + UNKNOWN_KEY,
                "Bearer " + TestApi.KEY + "0",
                "Bearer " + TestApi.KEY + " " + TestApi.KEY,
                "Bearer aie_",
                "Bearer aie_not-a-key");
    }

    /** None starts with aie_, so each is checked as a session token, and none is a valid one. */
    static List<String> badTokens() {
        String token = TestApi.sessionToken("eu_1");
        int signature = token.lastIndexOf('.') + 1;
        char flipped = token.charAt(signature) == 'A' ? 'B' : 'A';
        return List.of(
                "Bearer " + TestApi.expiredSessionToken(),
                "Bearer " + TestApi.foreignSessionToken(),
                "Bearer " + token.substring(0, signature) + flipped + token.substring(signature + 1),
                "Bearer " + token.substring(0, signature),
                "Bearer " + token + "x",
                "Bearer " + TestApi.KEY.toUpperCase(Locale.ROOT),
                "Bearer not-a-key",
                "Bearer");
    }

    @Test
    void aRequestWithoutAKeyIsUnauthorized() throws Exception {
        assertNoKey(send(request(PATH)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwYXNzd29yZA==", "Token abc", "Bearerx abc"})
    void anotherSchemeIsUnauthorized(String authorization) throws Exception {
        assertNoKey(send(request(PATH).header("Authorization", authorization)));
    }

    @Test
    void aKeyWithoutItsSchemeIsUnauthorized() throws Exception {
        assertNoKey(send(request(PATH).header("Authorization", TestApi.KEY)));
    }

    @ParameterizedTest
    @MethodSource("badKeys")
    void aKeyThatIsNotKnownIsUnauthorized(String authorization) throws Exception {
        assertBadKey(send(request(PATH).header("Authorization", authorization)));
    }

    /** Expired, signed with another key, changed, cut short, or no token at all: every failure looks the same. */
    @ParameterizedTest
    @MethodSource("badTokens")
    void aTokenThatIsNotValidIsUnauthorized(String authorization) throws Exception {
        assertBadCredential(send(request(PATH).header("Authorization", authorization)), BAD_CREDENTIAL);
    }

    /** An operation that takes no session token forbids a valid one, also where a key would pass. */
    @ParameterizedTest
    @ValueSource(strings = {PATH, "/v1/test/caller", "/v1/conversations", "/v1/sessions", "/v1", "/v1/conversations/conv_1/messages/msg_1/cancel"})
    void aValidSessionTokenIsForbidden(String path) throws Exception {
        assertForbiddenToken(send(request(path).header("Authorization", "Bearer " + TestApi.sessionToken("eu_1"))),
                SERVER_KEY_NEEDED);
    }

    /** The browser operations take a token (M2-12-2, M2-12-3); a HEAD is the GET without its body. */
    @Test
    void anOperationThatTakesATokenLetsAValidOneThrough() throws Exception {
        String token = "Bearer " + TestApi.sessionToken("eu_1");

        HttpResponse<String> started = send(request("/v1/conversations").header("Authorization", token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")));
        HttpResponse<String> head = send(request("/v1/conversations/conv_nothing").header("Authorization", token)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()));

        assertThat(started.statusCode()).isEqualTo(201);
        assertThat(started.headers().allValues("WWW-Authenticate")).isEmpty();
        assertThat(head.statusCode()).isEqualTo(404);
    }

    /** Where a token is taken, a request without credentials learns it may send either. */
    @Test
    void withoutCredentialsAnOperationThatTakesATokenNamesBoth() throws Exception {
        HttpResponse<String> response = send(request("/v1/conversations").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")));

        assertThat(problem(response, 401, "unauthorized").getDetail())
                .isEqualTo("Send an API key or a session token as Authorization: Bearer");
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer");
    }

    /** On-Behalf-Of comes before the operation, so it is refused where a token is taken too. */
    @Test
    void aSessionTokenWithOnBehalfOfIsForbiddenWhereATokenIsTaken() throws Exception {
        assertForbiddenToken(send(request("/v1/conversations/conv_nothing").header("Authorization", "Bearer " + TestApi.sessionToken("eu_1"))
                .header("On-Behalf-Of", "ten_lower")), NOT_ON_BEHALF);
    }

    /** A token is checked by its signature alone (blueprint 7.6), so no token reaches the key lookup. */
    @Test
    void aTokenIsCheckedWithoutTheKeyLookup() throws Exception {
        int before = LOOKUPS.get();

        send(request(PATH).header("Authorization", "Bearer " + TestApi.sessionToken("eu_1")));
        send(request(PATH).header("Authorization", "Bearer " + TestApi.foreignSessionToken()));

        assertThat(LOOKUPS.get()).isEqualTo(before);
    }

    /** A token names its own tenant, and the contract keeps On-Behalf-Of for server keys, whatever its value. */
    @ParameterizedTest
    @ValueSource(strings = {"ten_lower", "ten_other", "app_lower", ""})
    void aSessionTokenWithOnBehalfOfIsForbidden(String tenant) throws Exception {
        assertForbiddenToken(send(request("/v1/test/caller").header("Authorization", "Bearer " + TestApi.sessionToken("eu_1"))
                .header("On-Behalf-Of", tenant)), NOT_ON_BEHALF);
    }

    @Test
    void aTokenIsCheckedBeforeOnBehalfOf() throws Exception {
        assertBadCredential(send(request(PATH).header("Authorization", "Bearer " + TestApi.expiredSessionToken())
                .header("On-Behalf-Of", "ten_lower")), BAD_CREDENTIAL);
    }

    /** RFC 9110 11.1: the scheme ignores case, and spaces may separate it from the key. */
    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "bearer ", "BEARER ", "Bearer   "})
    void theSchemeIgnoresCaseAndExtraSpaces(String scheme) throws Exception {
        problem(send(request(PATH).header("Authorization", scheme + TestApi.KEY)), 404, "not_found");
    }

    @Test
    void aKnownKeyPassesAndTheRequestCarriesItsCaller() throws Exception {
        HttpResponse<String> response = send(withKey("/v1/test/caller"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readValue(response.body(), Caller.class)).isEqualTo(new Caller("key_test", "ten_test", "app_test"));
    }

    @Test
    void aKnownKeyOnAPathWithNoRouteIsNotFound() throws Exception {
        problem(send(withKey(PATH)), 404, "not_found");
    }

    /** Two keys would leave it open which one the request runs as. */
    @Test
    void twoAuthorizationHeadersAreRefused() throws IOException {
        String header = "Authorization: " + TestApi.AUTHORIZATION + "\r\n";

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + PATH + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" + header + header + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1))
                    .startsWith("HTTP/1.1 400")
                    .contains("Send one Authorization header");
        }
    }

    static List<String> notTenantIds() {
        return List.of("app_lower", "ten_", "ten_low er", "TEN_lower", "ten_lower-1", "ten_" + "x".repeat(61));
    }

    private static String raw(String headers) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + PATH + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\nAuthorization: "
                            + TestApi.AUTHORIZATION + "\r\n" + headers + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    /** Blueprint 6.5: the request runs as the lower tenant's application of the key's environment, under the platform's key. */
    @Test
    void aLowerTenantIsActedForAsItsApplicationOfTheKeysEnvironment() throws Exception {
        HttpResponse<String> response = send(withKey("/v1/test/caller").header("On-Behalf-Of", "ten_lower"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readValue(response.body(), Caller.class)).isEqualTo(new Caller("key_test", "ten_lower", "app_lowertest"));
    }

    @Test
    void aTenantThatIsNotALowerTenantIsForbidden() throws Exception {
        HttpResponse<String> response = send(withKey(PATH).header("On-Behalf-Of", "ten_other"));

        assertThat(problem(response, 403, "forbidden").getDetail()).isEqualTo("ten_other is not a lower tenant of this key's tenant");
    }

    @Test
    void aLowerTenantWithoutASingleApplicationIsForbidden() throws Exception {
        HttpResponse<String> response = send(withKey(PATH).header("On-Behalf-Of", "ten_empty"));

        assertThat(problem(response, 403, "forbidden").getDetail()).isEqualTo("ten_empty has no single test application to act as");
    }

    @ParameterizedTest
    @MethodSource("notTenantIds")
    void anOnBehalfOfThatIsNoTenantIdIsRefused(String value) throws Exception {
        HttpResponse<String> response = send(withKey(PATH).header("On-Behalf-Of", value));

        assertThat(problem(response, 400, "invalid_request").getDetail()).isEqualTo("Send one On-Behalf-Of with a tenant ID");
    }

    /** Two of them, or an empty one, would leave it open whom the request is for. */
    @ParameterizedTest
    @ValueSource(strings = {"On-Behalf-Of: ten_lower\r\nOn-Behalf-Of: ten_lower\r\n", "On-Behalf-Of:\r\n"})
    void twoOrAnEmptyOnBehalfOfAreRefused(String headers) throws Exception {
        assertThat(raw(headers)).startsWith("HTTP/1.1 400").contains("Send one On-Behalf-Of with a tenant ID");
    }

    @Test
    void theKeyIsCheckedBeforeOnBehalfOf() throws Exception {
        assertNoKey(send(request(PATH).header("On-Behalf-Of", "ten_lower")));
    }

    /** The version a request gets will depend on its application, so the key comes first. */
    @Test
    void theKeyIsCheckedBeforeTheVersion() throws Exception {
        assertNoKey(send(request(PATH).header("Engine-Version", "2020-01-01")));
        problem(send(withKey(PATH).header("Engine-Version", "2020-01-01")), 400, "invalid_request");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1", "/v1/", "/v1/conversations", "/%76%31/nothing-here", "/v1;a=b/nothing-here", "/v1/test/caller"})
    void everyPathOfThePublicApiNeedsAKey(String path) throws Exception {
        assertNoKey(send(request(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/healthz", "/v1x/nothing-here", "/v2/nothing-here", "/nothing-here/v1/"})
    void nothingElseNeedsAKey(String path) throws Exception {
        assertThat(send(request(path)).statusCode()).isNotEqualTo(401);
    }

    /** A lookup that cannot answer lets nothing through. The engine failed, not the caller. */
    @Test
    void aLookupThatFailsIsAnInternalError(CapturedOutput output) throws Exception {
        lookupFails = true;
        try {
            assertThat(problem(send(withKey(PATH)), 500, "internal_error").getDetail()).isNull();
        } finally {
            lookupFails = false;
        }

        assertThat(output.getOut()).contains("The request failed");
    }

    /** A key is a secret: neither a known one nor an unknown one reaches the logs, even when a request fails. */
    @Test
    void noKeyReachesTheLogs(CapturedOutput output) throws Exception {
        send(withKey("/v1/test/caller"));
        send(request(PATH).header("Authorization", "Bearer " + UNKNOWN_KEY));
        lookupFails = true;
        try {
            send(withKey(PATH));
        } finally {
            lookupFails = false;
        }

        assertThat(output.toString())
                .contains("The request failed")
                .doesNotContain(TestApi.KEY)
                .doesNotContain(UNKNOWN_KEY);
    }

    /** A token carries who the end user is, so neither a valid one nor a refused one reaches the logs. */
    @Test
    void noTokenReachesTheLogs(CapturedOutput output) throws Exception {
        String valid = TestApi.sessionToken("eu_1");
        String expired = TestApi.expiredSessionToken();

        send(request(PATH).header("Authorization", "Bearer " + valid));
        send(request("/v1/conversations/conv_nothing").header("Authorization", "Bearer " + valid));
        send(request(PATH).header("Authorization", "Bearer " + valid).header("On-Behalf-Of", "ten_lower"));
        send(request(PATH).header("Authorization", "Bearer " + expired));

        assertThat(output.toString()).doesNotContain(valid).doesNotContain(expired)
                .doesNotContain(valid.substring(valid.indexOf('.') + 1, valid.lastIndexOf('.')));
    }
}

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
    /** Well formed and unknown to the stand-in. */
    private static final String UNKNOWN_KEY = "aie_test_dev_" + "9876543210".repeat(4);

    private static volatile boolean lookupFails;
    private static ConfigurableApplicationContext context;
    private static int port;

    /** What a /v1 handler learns about the caller. */
    private record Caller(String keyId, String tenantId, String applicationId) {
    }

    @BeforeAll
    static void start() {
        context = TestApi.start(key -> {
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
        assertThat(problem(response, 401, "unauthorized").getDetail()).isEqualTo(BAD_KEY);
        assertThat(response.headers().allValues("WWW-Authenticate")).containsExactly("Bearer error=\"invalid_token\"");
    }

    static List<String> badKeys() {
        return List.of(
                "Bearer " + UNKNOWN_KEY,
                "Bearer " + TestApi.KEY + "0",
                "Bearer " + TestApi.KEY.toUpperCase(Locale.ROOT),
                "Bearer " + TestApi.KEY + " " + TestApi.KEY,
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
        assertThat(JSON.readValue(response.body(), Caller.class)).isEqualTo(new Caller("key_test", "ten_lower", "app_lower_test"));
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
}

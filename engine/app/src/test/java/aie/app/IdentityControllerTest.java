package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.Problem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** The identity operations on the real web stack, with stand-ins for the tenancy module. */
class IdentityControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInKeys KEYS = new TestApi.StandInKeys();
    private static final String CALLER = "key_test of ten_test/app_test";

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, KEYS, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void forgetCalls() {
        KEYS.calls.clear();
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> revoke(String keyId) throws Exception {
        return send(request("/v1/api-keys/" + keyId).header("Authorization", TestApi.AUTHORIZATION).DELETE());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(JSON.readValue(response.body(), Problem.class).getCode()).isEqualTo(code);
    }

    @Test
    void revokingAKeyOfTheApplicationAnswersNoContent() throws Exception {
        KEYS.allow("key_other");

        HttpResponse<String> response = revoke("key_other");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        assertThat(KEYS.calls).containsExactly(CALLER + " revokes key_other");
    }

    @Test
    void aKeyTheApplicationDoesNotHaveIsNotFound() throws Exception {
        assertProblem(revoke("key_missing"), 404, "not_found");
        assertThat(KEYS.calls).containsExactly(CALLER + " revokes key_missing");
    }

    @Test
    void aKeyRevokedOnceIsNotFoundTheSecondTime() throws Exception {
        KEYS.allow("key_twice");

        assertThat(revoke("key_twice").statusCode()).isEqualTo(204);
        assertProblem(revoke("key_twice"), 404, "not_found");
    }

    @Test
    void revokingNeedsAKey() throws Exception {
        assertProblem(send(request("/v1/api-keys/key_other").DELETE()), 401, "unauthorized");
        assertThat(KEYS.calls).isEmpty();
    }

    @Test
    void revokingOnBehalfOfALowerTenantIsRefusedForNow() throws Exception {
        HttpResponse<String> response = send(request("/v1/api-keys/key_other")
                .header("Authorization", TestApi.AUTHORIZATION).header("On-Behalf-Of", "ten_lower").DELETE());

        assertProblem(response, 403, "forbidden");
        assertThat(KEYS.calls).isEmpty();
    }

    @Test
    void aKeyCanOnlyBeDeleted() throws Exception {
        assertProblem(send(request("/v1/api-keys/key_other").header("Authorization", TestApi.AUTHORIZATION)), 405, "invalid_request");
    }

    /** These answer as their paths did before the controller existed, until their own points implement them. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/api-keys", "/v1/sessions", "/v1/anonymous-sessions"})
    void theOperationsOfLaterPointsAreNotFoundYet(String path) throws Exception {
        HttpResponse<String> response = send(request(path).header("Authorization", TestApi.AUTHORIZATION)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}")));

        assertProblem(response, 404, "not_found");
        assertThat(KEYS.calls).isEmpty();
    }
}

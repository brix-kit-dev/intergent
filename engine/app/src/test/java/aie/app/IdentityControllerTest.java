package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.CreatedApiKey;
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

    private static HttpResponse<String> create(String body) throws Exception {
        return send(request("/v1/api-keys").header("Authorization", TestApi.AUTHORIZATION)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)));
    }

    private static String detail(HttpResponse<String> response) {
        return JSON.readValue(response.body(), Problem.class).getDetail();
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

    /** On-Behalf-Of: the platform's key revokes a key of the lower tenant's application. */
    @Test
    void aPlatformRevokesAKeyOfItsLowerTenant() throws Exception {
        KEYS.allow("key_lower");

        HttpResponse<String> response = send(request("/v1/api-keys/key_lower")
                .header("Authorization", TestApi.AUTHORIZATION).header("On-Behalf-Of", "ten_lower").DELETE());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(KEYS.calls).containsExactly("key_test of ten_lower/app_lower_test revokes key_lower");
    }

    @Test
    void actingForATenantThatIsNotALowerTenantIsForbidden() throws Exception {
        HttpResponse<String> response = send(request("/v1/api-keys/key_other")
                .header("Authorization", TestApi.AUTHORIZATION).header("On-Behalf-Of", "ten_other").DELETE());

        assertProblem(response, 403, "forbidden");
        assertThat(KEYS.calls).isEmpty();
    }

    @Test
    void aKeyCanOnlyBeDeleted() throws Exception {
        assertProblem(send(request("/v1/api-keys/key_other").header("Authorization", TestApi.AUTHORIZATION)), 405, "invalid_request");
    }

    @Test
    void creatingAKeyAnswersCreatedWithTheKeyOnce() throws Exception {
        HttpResponse<String> response = create("{\"name\":\"rotation\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        CreatedApiKey created = JSON.readValue(response.body(), CreatedApiKey.class);
        assertThat(created.getId()).isEqualTo("key_created1");
        assertThat(created.getKey()).isEqualTo("aie_test_dev_" + "5".repeat(40));
        assertThat(created.getName()).isEqualTo("rotation");
        assertThat(response.body()).contains("\"created_at\":\"2026-11-08T00:00:00Z\"").doesNotContain("expires_at");
        assertThat(KEYS.calls).containsExactly(CALLER + " creates rotation");
    }

    @Test
    void aKeyNeedsNoName() throws Exception {
        HttpResponse<String> response = create("{}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).doesNotContain("\"name\"");
        assertThat(KEYS.calls).containsExactly(CALLER + " creates null");
    }

    @Test
    void anExpiryInTheFutureIsKept() throws Exception {
        HttpResponse<String> response = create("{\"expires_at\":\"2030-01-01T09:00:00+09:00\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"expires_at\":\"2030-01-01T00:00:00Z\"");
        assertThat(KEYS.calls).containsExactly(CALLER + " creates null until 2030-01-01T00:00:00Z");
    }

    /** A key that would be dead on arrival is a mistake of the caller, so it gets no key at all. */
    @ParameterizedTest
    @ValueSource(strings = {"2020-01-01T00:00:00Z", "2026-11-08T00:00:00Z"})
    void anExpiryThatHasPassedIsRefused(String expiry) throws Exception {
        HttpResponse<String> response = create("{\"expires_at\":\"" + expiry + "\"}");

        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo("expires_at must be in the future");
        assertThat(KEYS.calls).isEmpty();
    }

    /** The contract counts characters, so a name of 100 symbols outside the basic plane passes too. */
    @Test
    void aNameMayHaveOneHundredCharactersAndNoMore() throws Exception {
        assertThat(create("{\"name\":\"" + "n".repeat(100) + "\"}").statusCode()).isEqualTo(201);
        assertThat(create("{\"name\":\"" + "\uD83D\uDD11".repeat(100) + "\"}").statusCode()).isEqualTo(201);

        HttpResponse<String> tooLong = create("{\"name\":\"" + "n".repeat(101) + "\"}");

        assertProblem(tooLong, 400, "invalid_request");
        assertThat(detail(tooLong)).isEqualTo("name may be at most 100 characters");
        assertThat(KEYS.calls).hasSize(2);
    }

    @Test
    void creatingNeedsAKey() throws Exception {
        assertProblem(send(request("/v1/api-keys").header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}"))),
                401, "unauthorized");
        assertThat(KEYS.calls).isEmpty();
    }

    /** These answer as their paths did before the controller existed, until their own points implement them. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/sessions", "/v1/anonymous-sessions"})
    void theOperationsOfLaterPointsAreNotFoundYet(String path) throws Exception {
        HttpResponse<String> response = send(request(path).header("Authorization", TestApi.AUTHORIZATION)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}")));

        assertProblem(response, 404, "not_found");
        assertThat(KEYS.calls).isEmpty();
    }
}

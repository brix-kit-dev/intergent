package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.http.RequestProbes;
import aie.http.api.model.Problem;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Idempotency-Key of blueprint 6.2 on the real api role: the real filter, the real keys on the database, and test
 * routes that count how often they really ran.
 */
@Testcontainers
class IdempotencyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String RUN = "/v1/test/run";
    private static final String WHEN_FREE = "/v1/test/run-when-free";
    private static final String BAD_HEADER = "Send one Idempotency-Key of 1 to 255 characters";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static ConfigurableApplicationContext context;
    private static int port;
    private static String tenant;
    private static String application;
    private static String key;
    /** A key of another application of the same tenant. */
    private static String otherKey;

    @BeforeAll
    static void startOnTheRealDatabase() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        context = ApiRole.start("--server.port=0", "--engine.database.url=" + DATABASE.getJdbcUrl(),
                "--engine.app.password=" + TestDatabase.APP_PASSWORD, TestApi.SESSION_KEY_SETTING, TestApi.REGION_SETTING, TestApi.AUDIT_KEY_SETTING, TestApi.BUDGET_SETTING);
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        tenant = provisioning.createTenant("jp1", null);
        application = provisioning.createApplication(tenant, "test", "internal");
        key = provisioning.createKey(tenant, application).key();
        otherKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void countFromZero() {
        RequestProbes.RUNS.set(0);
        RequestProbes.BUSY.set(0);
        RequestProbes.hold = new CountDownLatch(0);
    }

    private static String newKey() {
        return "retry-" + UUID.randomUUID();
    }

    private static HttpResponse<String> post(String path, @Nullable String apiKey, @Nullable String idempotencyKey, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        if (apiKey != null) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** For headers the HTTP client will not send: an empty one, or two of them. */
    private static String raw(String headers) throws IOException {
        String body = "{}";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("POST " + RUN + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n"
                            + "Authorization: Bearer " + key + "\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length() + "\r\n" + headers + "\r\n" + body)
                    .getBytes(StandardCharsets.ISO_8859_1));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static Problem problem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo(code);
        return problem;
    }

    private static List<String> rowsFor(String idempotencyKey) throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT tenant_id || ' ' || application_id || ' ' || response_status || ' ' || response_body
                FROM engine.tenancy_idempotency_keys WHERE idempotency_key = '%s'
                """.formatted(idempotencyKey));
    }

    static List<String> badHeaders() {
        return List.of("Idempotency-Key:\r\n", "Idempotency-Key: " + "k".repeat(256) + "\r\n", "Idempotency-Key: a\r\nIdempotency-Key: b\r\n");
    }

    @Test
    void aRetryGetsTheFirstAnswerAndTheWorkRunsOnce() throws Exception {
        String once = newKey();

        HttpResponse<String> first = post(RUN, key, once, "{\"amount\":1}");
        HttpResponse<String> retry = post(RUN, key, once, "{\"amount\":1}");

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.body()).isEqualTo("{\"run\":1}");
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(retry.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(retry.headers().firstValue("Request-Id")).isPresent().isNotEqualTo(first.headers().firstValue("Request-Id"));
        assertThat(RequestProbes.RUNS).hasValue(1);
    }

    @Test
    void withoutTheHeaderEveryRequestRuns() throws Exception {
        assertThat(post(RUN, key, null, "{}").body()).isEqualTo("{\"run\":1}");
        assertThat(post(RUN, key, null, "{}").body()).isEqualTo("{\"run\":2}");
    }

    @Test
    void theSameKeyWithAnotherBodyIsRefused() throws Exception {
        String once = newKey();
        post(RUN, key, once, "{\"amount\":1}");

        Problem problem = problem(post(RUN, key, once, "{\"amount\":2}"), 422, "idempotency_key_reused");

        assertThat(problem.getDetail()).isEqualTo("This Idempotency-Key belongs to a different request");
        assertThat(RequestProbes.RUNS).hasValue(1);
    }

    @Test
    void theSameKeyOnAnotherOperationIsRefused() throws Exception {
        String once = newKey();
        post(RUN, key, once, "{}");

        problem(post("/v1/test/run-and-fail", key, once, "{}"), 422, "idempotency_key_reused");
        assertThat(RequestProbes.RUNS).hasValue(1);
    }

    /** Blueprint 6.4: a failure is replayed as it was too, so a retry that should run again takes a new key. */
    @Test
    void aFailureIsReplayedAsItWas() throws Exception {
        String once = newKey();

        HttpResponse<String> first = post("/v1/test/run-and-fail", key, once, "{}");
        HttpResponse<String> retry = post("/v1/test/run-and-fail", key, once, "{}");

        problem(first, 500, "internal_error");
        problem(retry, 500, "internal_error");
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(RequestProbes.RUNS).hasValue(1);
    }

    /**
     * A 429 says that nothing ran. It is not stored and the key is let go, so a retry with the same key after Retry-After
     * really runs, and the answer of that run is the one kept.
     */
    @Test
    void aTurnAwayWith429KeepsNothingAndTheSameKeyRunsLater() throws Exception {
        RequestProbes.BUSY.set(1);
        String once = newKey();

        HttpResponse<String> turnedAway = post(WHEN_FREE, key, once, "{}");
        problem(turnedAway, 429, "rate_limited");
        assertThat(turnedAway.headers().firstValue("Retry-After")).contains("5");
        assertThat(rowsFor(once)).isEmpty();

        HttpResponse<String> retry = post(WHEN_FREE, key, once, "{}");
        HttpResponse<String> again = post(WHEN_FREE, key, once, "{}");

        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo("{\"run\":2}");
        assertThat(again.statusCode()).isEqualTo(201);
        assertThat(again.body()).isEqualTo(retry.body());
        assertThat(RequestProbes.RUNS).hasValue(2);
        assertThat(rowsFor(once)).singleElement().asString().contains(" 201 ");
    }

    /** Each 429 is a fresh try, never a replay of the last one. */
    @Test
    void a429IsNeverReplayed() throws Exception {
        RequestProbes.BUSY.set(2);
        String once = newKey();

        problem(post(WHEN_FREE, key, once, "{}"), 429, "rate_limited");
        problem(post(WHEN_FREE, key, once, "{}"), 429, "rate_limited");
        HttpResponse<String> third = post(WHEN_FREE, key, once, "{}");

        assertThat(third.statusCode()).isEqualTo(201);
        assertThat(third.body()).isEqualTo("{\"run\":3}");
        assertThat(RequestProbes.RUNS).hasValue(3);
    }

    @Test
    void aKeyWhoseRequestStillRunsIsInUse() throws Exception {
        RequestProbes.hold = new CountDownLatch(1);
        String once = newKey();
        CompletableFuture<HttpResponse<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                return post("/v1/test/run-slowly", key, once, "{}");
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
        await().atMost(10, TimeUnit.SECONDS).until(() -> RequestProbes.RUNS.get() == 1);

        Problem problem = problem(post("/v1/test/run-slowly", key, once, "{}"), 409, "idempotency_key_in_use");
        RequestProbes.hold.countDown();

        assertThat(problem.getDetail()).isEqualTo("A request with this Idempotency-Key is still running");
        assertThat(first.get(30, TimeUnit.SECONDS).body()).isEqualTo("{\"run\":1}");
        assertThat(post("/v1/test/run-slowly", key, once, "{}").body()).isEqualTo("{\"run\":1}");
        assertThat(RequestProbes.RUNS).hasValue(1);
    }

    @Test
    void eachApplicationHasItsOwnKeys() throws Exception {
        String shared = newKey();

        assertThat(post(RUN, key, shared, "{}").body()).isEqualTo("{\"run\":1}");
        assertThat(post(RUN, otherKey, shared, "{}").body()).isEqualTo("{\"run\":2}");
    }

    @ParameterizedTest
    @MethodSource("badHeaders")
    void aBadHeaderIsRefused(String headers) throws Exception {
        assertThat(raw(headers)).startsWith("HTTP/1.1 400").contains("\"code\":\"invalid_request\"").contains(BAD_HEADER);
        assertThat(RequestProbes.RUNS).hasValue(0);
    }

    @Test
    void keysOfOneAndOfTwoHundredFiftyFiveCharactersAreTaken() throws Exception {
        assertThat(post(RUN, key, "k", "{}").statusCode()).isEqualTo(201);
        assertThat(post(RUN, key, "k".repeat(255), "{}").statusCode()).isEqualTo(201);
    }

    @Test
    void aBodyOverTheLimitIsRefused() throws Exception {
        String atTheLimit = "{\"text\":\"" + "x".repeat(1024 * 1024 - 11) + "\"}";
        assertThat(atTheLimit.length()).isEqualTo(1024 * 1024);

        problem(post(RUN, key, newKey(), atTheLimit.replace("{\"text\":\"", "{\"text\":\"x")), 413, "invalid_request");
        assertThat(post(RUN, key, newKey(), atTheLimit).statusCode()).isEqualTo(201);
        assertThat(RequestProbes.RUNS).hasValue(1);
    }

    /** A field marked as not stored is in the first answer only, and nowhere in the database. */
    @Test
    void aFieldThatMustNotBeStoredIsLeftOutOfTheReplay() throws Exception {
        String once = newKey();

        HttpResponse<String> first = post("/v1/test/secret", key, once, "{}");
        HttpResponse<String> retry = post("/v1/test/secret", key, once, "{}");

        assertThat(first.body()).contains("\"secret\":\"s3cret-1\"").contains("\"run\":1");
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).isEqualTo("{\"run\":1}");
        assertThat(rowsFor(once).getFirst()).doesNotContain("s3cret");
        assertThat(RequestProbes.RUNS).hasValue(1);
    }

    /** When the marked fields cannot be taken out, the body is not kept at all, so the secret is not either. */
    @Test
    void aSecretThatCannotBeTakenOutKeepsNoBody() throws Exception {
        String once = newKey();

        HttpResponse<String> first = post("/v1/test/secret-text", key, once, "{}");
        HttpResponse<String> retry = post("/v1/test/secret-text", key, once, "{}");

        assertThat(first.body()).isEqualTo("s3cret-1");
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).isEmpty();
        assertThat(rowsFor(once)).containsExactly(tenant + " " + application + " 200 ");
        assertThat(RequestProbes.RUNS).hasValue(1);
    }

    /** The filter reads the body for its fingerprint, and the controller still gets all of it. */
    @Test
    void theControllerStillReadsTheWholeBody() throws Exception {
        String text = "y".repeat(1024 * 1024 - 11);

        HttpResponse<String> response = post("/v1/test/echo", key, newKey(), "{\"text\":\"" + text + "\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"text\":\"" + text + "\"}");
    }

    /** The key check refuses before the claim, so the same Idempotency-Key still works once the caller is known. */
    @Test
    void aRequestRefusedBeforeTheClaimLeavesTheKeyFree() throws Exception {
        String once = newKey();

        problem(post(RUN, null, once, "{}"), 401, "unauthorized");

        assertThat(post(RUN, key, once, "{}").body()).isEqualTo("{\"run\":1}");
    }

    @Test
    void onlyPostsUseTheKey() throws Exception {
        String once = newKey();
        HttpRequest delete = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/api-keys/key_missing"))
                .header("Authorization", "Bearer " + key).header("Idempotency-Key", once).DELETE().build();

        assertThat(HttpClient.newHttpClient().send(delete, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(rowsFor(once)).isEmpty();
    }

    @Test
    void theFirstAnswerIsKeptInTheRowOfItsTenantAndApplication() throws Exception {
        String once = newKey();

        post(RUN, key, once, "{}");

        assertThat(rowsFor(once)).containsExactly(tenant + " " + application + " 201 {\"run\":1}");
    }
}

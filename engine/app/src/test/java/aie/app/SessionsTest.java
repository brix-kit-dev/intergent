package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.http.api.model.Problem;
import aie.http.api.model.Session;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/sessions} on the real api role and a real database: the end users it makes, the tokens it signs,
 * what a replay gives back, and a platform acting for its lower tenant. IdentityControllerTest checks every limit.
 */
@Testcontainers
class SessionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static ConfigurableApplicationContext context;
    private static int port;
    private static String tenant;
    private static String application;
    private static String key;
    /** A key of another application of the same tenant. */
    private static String otherKey;
    private static String lowerTenant;
    private static String lowerApplication;

    @BeforeAll
    static void startOnTheRealDatabase() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(
                        "--engine.database.url=" + DATABASE.getJdbcUrl(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD))
                .isZero();
        context = ApiRole.start("--server.port=0", "--engine.database.url=" + DATABASE.getJdbcUrl(),
                "--engine.app.password=" + TestDatabase.APP_PASSWORD, TestApi.SESSION_KEY_SETTING);
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        tenant = provisioning.createTenant("jp1", null);
        application = provisioning.createApplication(tenant, "test", "internal");
        key = provisioning.createKey(tenant, application).key();
        otherKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        lowerTenant = provisioning.createTenant("jp1", tenant);
        lowerApplication = provisioning.createApplication(lowerTenant, "test", "internal");
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static HttpResponse<String> post(String apiKey, String body, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/sessions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Session answer(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readValue(response.body(), Session.class);
    }

    /** The end users of a host ID, as tenant, application and ID, read past row-level security. */
    private static List<String> endUsers(String externalId) throws SQLException {
        return TestDatabase.firstColumn(DATABASE, """
                SELECT tenant_id || ' ' || application_id || ' ' || end_user_id
                FROM engine.tenancy_end_users WHERE external_id = '%s' ORDER BY created_at
                """.formatted(externalId));
    }

    private static String newHostId() {
        return "host-" + UUID.randomUUID();
    }

    @Test
    void aSessionIsForAnEndUserOfTheKeysApplication() throws Exception {
        String hostId = newHostId();

        Session answer = answer(post(key, "{\"external_user_id\":\"" + hostId + "\",\"scopes\":[\"faq\"],\"locale\":\"ja-JP\"}"));

        assertThat(endUsers(hostId)).containsExactly(tenant + " " + application + " " + answer.getEndUserId());
        SessionTokens.Verified verified = TestApi.SESSION_TOKENS.verify(answer.getToken()).orElseThrow();
        assertThat(verified.session()).isEqualTo(new SessionTokens.Session(
                tenant, application, answer.getEndUserId(), IdentityLevel.VERIFIED, List.of("faq"), Map.of(), "ja-JP"));
    }

    /** One end user per host ID and application: the same ID again is the same one, another application has its own. */
    @Test
    void eachApplicationHasItsOwnEndUserForAHostId() throws Exception {
        String hostId = newHostId();
        String body = "{\"external_user_id\":\"" + hostId + "\"}";

        String first = answer(post(key, body)).getEndUserId();
        String again = answer(post(key, body)).getEndUserId();
        String other = answer(post(otherKey, body)).getEndUserId();

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
        assertThat(endUsers(hostId)).hasSize(2);
    }

    /** The engine never stores a token, so a replay gives the first answer without it, and the row holds none. */
    @Test
    void aReplayAnswersTheSameWithoutTheToken() throws Exception {
        String idempotencyKey = "retry-" + UUID.randomUUID();
        String body = "{\"external_user_id\":\"" + newHostId() + "\",\"scopes\":[\"faq\"]}";

        HttpResponse<String> first = post(key, body, "Idempotency-Key", idempotencyKey);
        HttpResponse<String> replay = post(key, body, "Idempotency-Key", idempotencyKey);

        Session original = answer(first);
        Session replayed = answer(replay);
        assertThat(original.getToken()).isNotBlank();
        assertThat(replayed.getToken()).isNull();
        assertThat(replay.body()).doesNotContain("token").doesNotContain(original.getToken());
        assertThat(replayed.getEndUserId()).isEqualTo(original.getEndUserId());
        assertThat(replayed.getExpiresAt()).isEqualTo(original.getExpiresAt());
        assertThat(replayed.getIdentityLevel()).isEqualTo("verified");
        assertThat(replayed.getScopes()).containsExactly("faq");
        assertThat(replay.headers().firstValue("Request-Id")).isNotEqualTo(first.headers().firstValue("Request-Id"));
        assertThat(TestDatabase.firstColumn(DATABASE,
                "SELECT response_body FROM engine.tenancy_idempotency_keys WHERE idempotency_key = '" + idempotencyKey + "'"))
                .singleElement().asString().contains(original.getEndUserId()).doesNotContain("token");
    }

    /** On-Behalf-Of: the end user and the token belong to the lower tenant's application, not to the platform's. */
    @Test
    void aPlatformMakesASessionForAnEndUserOfItsLowerTenant() throws Exception {
        String hostId = newHostId();

        Session answer = answer(post(key, "{\"external_user_id\":\"" + hostId + "\"}", "On-Behalf-Of", lowerTenant));

        assertThat(endUsers(hostId)).containsExactly(lowerTenant + " " + lowerApplication + " " + answer.getEndUserId());
        SessionTokens.Session session = TestApi.SESSION_TOKENS.verify(answer.getToken()).orElseThrow().session();
        assertThat(session.tenantId()).isEqualTo(lowerTenant);
        assertThat(session.applicationId()).isEqualTo(lowerApplication);
    }

    @Test
    void aRefusedRequestLeavesNoEndUser() throws Exception {
        String hostId = newHostId();

        HttpResponse<String> response = post(key, "{\"external_user_id\":\"" + hostId + "\",\"locale\":\"ja_JP\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(endUsers(hostId)).isEmpty();
    }

    /**
     * S-10 on the real api: a token it issued makes no other token and no key, and no operation takes one yet
     * (M2-12-1). The token is refused before anything runs, so the host ID it names gets no end user, and the key it
     * would make does not exist. A token this api did not sign is not valid at all.
     */
    @Test
    void aTokenTheApiIssuedIsForbiddenWhereAServerKeyIsNeeded() throws Exception {
        String token = answer(post(key, "{\"external_user_id\":\"" + newHostId() + "\"}")).getToken();
        String hostId = newHostId();
        String keyName = "made-with-a-token-" + UUID.randomUUID();

        List<HttpResponse<String>> refused = List.of(
                post(token, "{\"external_user_id\":\"" + hostId + "\"}"),
                send(token, "POST", "/v1/api-keys", "{\"name\":\"" + keyName + "\"}"),
                send(token, "DELETE", "/v1/api-keys/key_1", null),
                send(token, "POST", "/v1/conversations", "{}"));

        assertThat(refused).allSatisfy(response -> {
            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(JSON.readValue(response.body(), Problem.class).getCode()).isEqualTo("forbidden");
        });
        assertThat(endUsers(hostId)).isEmpty();
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM engine.tenancy_api_keys WHERE name = '" + keyName + "'"))
                .containsExactly("0");
        assertThat(post(TestApi.foreignSessionToken(), "{\"external_user_id\":\"" + hostId + "\"}").statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> send(String credential, String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + credential);
        if (body == null) {
            request.method(method, BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

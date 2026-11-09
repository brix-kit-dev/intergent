package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.api.Provisioning.IssuedKey;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.core.tenancy.internal.DatabaseProvisioning;
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
 * POST /v1/anonymous-sessions on the real api role and a real database: a site key issued the way admin issues it, the
 * anonymous end users it makes, a replay, and a revoked key. AnonymousSessionsTest checks the rest with stand-ins.
 */
@Testcontainers
class AnonymousSessionsDatabaseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SHOP = "https://shop.example.com";

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static ConfigurableApplicationContext context;
    private static int port;
    private static Provisioning provisioning;
    private static String tenant;
    private static String application;

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
        provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        tenant = provisioning.createTenant("jp1", null);
        application = provisioning.createApplication(tenant, "test", "internal");
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static IssuedKey newSiteKey() {
        return provisioning.createSiteKey(tenant, application, List.of(SHOP));
    }

    private static HttpResponse<String> post(String origin, String siteKey, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/anonymous-sessions"))
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .POST(BodyPublishers.ofString("{\"site_key\":\"" + siteKey + "\"}"));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> rows(String sql) throws SQLException {
        return TestDatabase.firstColumn(DATABASE, sql);
    }

    @Test
    void aSiteKeyGetsATokenForANewAnonymousEndUserOfItsApplication() throws Exception {
        HttpResponse<String> response = post(SHOP, newSiteKey().key());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(SHOP);
        Session answer = JSON.readValue(response.body(), Session.class);
        assertThat(rows("SELECT tenant_id || ' ' || application_id || ' ' || (external_id IS NULL)::text "
                + "FROM engine.tenancy_end_users WHERE end_user_id = '" + answer.getEndUserId() + "'"))
                .containsExactly(tenant + " " + application + " true");
        assertThat(TestApi.SESSION_TOKENS.verify(answer.getToken()).orElseThrow().session()).isEqualTo(new SessionTokens.Session(
                tenant, application, answer.getEndUserId(), IdentityLevel.ANONYMOUS, List.of(), Map.of(), null));
    }

    /** The key is scoped to the site key's application, and the token is never stored, so a replay has none. */
    @Test
    void aReplayAnswersTheSameWithoutTheToken() throws Exception {
        String siteKey = newSiteKey().key();
        String retry = "retry-" + UUID.randomUUID();

        Session first = JSON.readValue(post(SHOP, siteKey, "Idempotency-Key", retry).body(), Session.class);
        HttpResponse<String> replay = post(SHOP, siteKey, "Idempotency-Key", retry);

        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).doesNotContain("token").contains(first.getEndUserId());
        assertThat(replay.headers().firstValue("Access-Control-Allow-Origin")).contains(SHOP);
        assertThat(rows("SELECT tenant_id || ' ' || application_id || ' ' || (response_body NOT LIKE '%token%')::text "
                + "FROM engine.tenancy_idempotency_keys WHERE idempotency_key = '" + retry + "'"))
                .containsExactly(tenant + " " + application + " true");
        assertThat(rows("SELECT count(*) FROM engine.tenancy_end_users WHERE end_user_id = '" + first.getEndUserId() + "'"))
                .containsExactly("1");
    }

    /** Another origin and a revoked key are refused before anything is made. */
    @Test
    void anotherOriginAndARevokedKeyMakeNothing() throws Exception {
        IssuedKey issued = newSiteKey();
        String before = rows("SELECT count(*) FROM engine.tenancy_end_users").getFirst();

        assertThat(post("https://evil.example", issued.key()).statusCode()).isEqualTo(403);
        assertThat(provisioning.revokeSiteKey(tenant, issued.keyId())).isTrue();
        assertThat(post(SHOP, issued.key()).statusCode()).isEqualTo(401);

        assertThat(rows("SELECT count(*) FROM engine.tenancy_end_users")).containsExactly(before);
    }
}

package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.api.Provisioning.IssuedKey;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.http.api.model.CreatedApiKey;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ApiRoleTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static final Duration SETTLES = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void createRolesAndStart() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        assertThat(MigrateRole.run(address(), "--engine.owner.password=" + TestDatabase.OWNER_PASSWORD)).isZero();
        context = ApiRole.start("--server.port=0", address(), password(TestDatabase.APP_PASSWORD));
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    private static String address() {
        return "--engine.database.url=" + DATABASE.getJdbcUrl();
    }

    private static String password(String value) {
        return "--engine.app.password=" + value;
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int revoke(String keyId, String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/api-keys/" + keyId))
                .header("Authorization", "Bearer " + key)
                .DELETE()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static HttpResponse<String> createKey(String key, String idempotencyKey, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/api-keys"))
                .header("Authorization", "Bearer " + key)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int status(String path, String key) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + key)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static boolean accepts(InetAddress address) {
        return accepts(address, port);
    }

    private static boolean accepts(InetAddress address, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Connections the server holds for engine_app right now. The api started for this class holds a full pool. */
    private static long engineAppConnections() throws SQLException {
        return Long.parseLong(TestDatabase.firstColumn(DATABASE, "SELECT count(*) FROM pg_stat_activity WHERE usename = 'engine_app'").get(0));
    }

    private static boolean engineAppHolds(long connections) throws SQLException {
        return engineAppConnections() == connections;
    }

    private static String currentUserOf(PgPool pool) {
        return pool.inSystemTx(tx -> tx.query("SELECT current_user AS who", row -> row.string("who")).get(0));
    }

    private static void awaitOnlyTheClassPool() {
        await().during(Duration.ofSeconds(1)).atMost(SETTLES).until(() -> engineAppHolds(ApiRole.POOL_SIZE));
    }

    private static int freePort() throws IOException {
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return free.getLocalPort();
        }
    }

    @Test
    void healthAnswersOk() throws Exception {
        HttpResponse<String> response = get("/healthz");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"status\":\"ok\"}");
    }

    @Test
    void anUnknownPathIsNotFound() throws Exception {
        assertThat(get("/nothing-here").statusCode()).isEqualTo(404);
    }

    @Test
    void requestsRunOnVirtualThreads() throws Exception {
        assertThat(get("/test/thread").body()).isEqualTo("{\"virtual\":true}");
    }

    @Test
    void listensOnLocalhostOnly() throws Exception {
        List<InetAddress> outside = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                .flatMap(nic -> Collections.list(nic.getInetAddresses()).stream())
                .filter(address -> !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                .toList();
        assertThat(outside).as("this test needs a non-loopback address").isNotEmpty();
        assertThat(outside).noneMatch(ApiRoleTest::accepts);
        assertThat(accepts(InetAddress.getByName("127.0.0.1"))).isTrue();
    }

    @Test
    void aRoleThatFailsTheSelfCheckIsRefusedAndOpensNoPort(CapturedOutput output) throws Exception {
        int unused = freePort();
        TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app BYPASSRLS");
        try {
            assertThat(EngineApplication.launch(new String[] {
                            "--role=api", "--server.port=" + unused, address(), password(TestDatabase.APP_PASSWORD)}))
                    .isEqualTo(EngineApplication.EXIT_FAILED);
        } finally {
            TestDatabase.asAdmin(DATABASE, "ALTER ROLE engine_app NOBYPASSRLS");
        }

        assertThat(output.getErr()).contains("Startup refused: engine_app may not serve traffic: it has BYPASSRLS");
        assertThat(accepts(InetAddress.getByName("127.0.0.1"), unused)).isFalse();
        awaitOnlyTheClassPool();
    }

    /** The pool fills up to its size and keeps those connections, all of them as engine_app. */
    @Test
    void theApiHoldsAPoolOfEngineAppConnections() {
        awaitOnlyTheClassPool();

        assertThat(currentUserOf(context.getBean(PgPool.class))).isEqualTo("engine_app");
    }

    @Test
    void stoppingTheApiClosesItsConnections() {
        awaitOnlyTheClassPool();
        ConfigurableApplicationContext second = ApiRole.start("--server.port=0", address(), password(TestDatabase.APP_PASSWORD));
        await().atMost(SETTLES).until(() -> engineAppHolds(2L * ApiRole.POOL_SIZE));

        second.close();

        awaitOnlyTheClassPool();
    }

    /** The port fails as the context starts, after it has made its beans, so the context closes the pool. */
    @Test
    void anApiThatCannotStartClosesItsPool() throws IOException {
        awaitOnlyTheClassPool();
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertThatThrownBy(() -> ApiRole.start("--server.port=" + taken.getLocalPort(), address(), password(TestDatabase.APP_PASSWORD)))
                    .hasStackTraceContaining("already in use");
        }

        awaitOnlyTheClassPool();
    }

    /** A bad setting fails the context before it has made its beans, so only the role can close the pool. */
    @Test
    void anApiWithABadSettingClosesItsPool() {
        awaitOnlyTheClassPool();

        assertThatThrownBy(() -> ApiRole.start("--server.port=not-a-port", address(), password(TestDatabase.APP_PASSWORD)))
                .hasStackTraceContaining("not-a-port");

        awaitOnlyTheClassPool();
    }

    /** The filter on the real lookup: the pool, the system function, and a key issued the way admin issues it. */
    @Test
    void anIssuedKeyPassesAndAnyOtherIsRefused() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String key = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();

        assertThat(status("/v1/nothing-here", key)).isEqualTo(404);
        assertThat(status("/v1/nothing-here", ApiKeys.issue("test", "jp1"))).isEqualTo(401);
    }

    /** DELETE /v1/api-keys/{key_id} on the real database: another key of the application, then the caller's own. */
    @Test
    void aKeyRevokesAnotherKeyOfItsApplicationAndThenItself() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String application = provisioning.createApplication(tenant, "test", "internal");
        IssuedKey first = provisioning.createKey(tenant, application);
        IssuedKey second = provisioning.createKey(tenant, application);
        IssuedKey third = provisioning.createKey(tenant, application);
        String elsewhere = provisioning.createTenant("jp1", null);
        IssuedKey foreign = provisioning.createKey(elsewhere, provisioning.createApplication(elsewhere, "test", "internal"));

        assertThat(revoke(second.keyId(), first.key())).isEqualTo(204);
        assertThat(status("/v1/nothing-here", second.key())).isEqualTo(401);
        assertThat(status("/v1/nothing-here", first.key())).isEqualTo(404);
        assertThat(revoke(foreign.keyId(), first.key())).isEqualTo(404);
        assertThat(status("/v1/nothing-here", foreign.key())).isEqualTo(404);
        assertThat(revoke(first.keyId(), first.key())).isEqualTo(204);
        assertThat(status("/v1/nothing-here", first.key())).isEqualTo(401);
        assertThat(revoke(first.keyId(), third.key())).isEqualTo(404);
    }

    /** POST /v1/api-keys on the real database, retried with its Idempotency-Key: the retry never sees the key. */
    @Test
    void aKeyCreatesAnotherKeyAndARetryGetsItWithoutTheSecret() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String first = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();

        HttpResponse<String> created = createKey(first, "rotate-1", "{\"name\":\"second\"}");
        HttpResponse<String> retry = createKey(first, "rotate-1", "{\"name\":\"second\"}");

        assertThat(created.statusCode()).isEqualTo(201);
        CreatedApiKey second = JSON.readValue(created.body(), CreatedApiKey.class);
        assertThat(second.getKey()).startsWith("aie_test_jp1_");
        assertThat(status("/v1/nothing-here", second.getKey())).isEqualTo(404);
        assertThat(status("/v1/nothing-here", first)).isEqualTo(404);
        assertThat(retry.statusCode()).isEqualTo(201);
        CreatedApiKey replayed = JSON.readValue(retry.body(), CreatedApiKey.class);
        assertThat(replayed.getId()).isEqualTo(second.getId());
        assertThat(replayed.getKey()).isNull();
        assertThat(TestDatabase.firstColumn(DATABASE,
                        "SELECT response_body FROM engine.tenancy_idempotency_keys WHERE idempotency_key = 'rotate-1'"))
                .singleElement().asString().contains(second.getId()).doesNotContain(second.getKey());
    }

    @Test
    void missingTheAddressIsRefusedByName(CapturedOutput output) {
        assertThat(ApiRole.run("--engine.database.url=", password("x"))).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Startup refused: ENGINE_DATABASE_URL is required");
    }

    @Test
    void missingThePasswordIsRefusedByName(CapturedOutput output) {
        assertThat(ApiRole.run(address(), password(""))).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Startup refused: ENGINE_APP_PASSWORD is required");
    }

    @Test
    void aWrongPasswordIsRefusedWithoutPrintingIt(CapturedOutput output) {
        assertThat(ApiRole.run(address(), password("wrong-secret-value"))).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("password authentication failed");
        assertThat(output.toString()).doesNotContain("wrong-secret-value");
    }

    @Test
    void aBadAddressIsRefusedWithoutPrintingIt(CapturedOutput output) {
        assertThat(ApiRole.run("--engine.database.url=mysql://secret-host/engine", password("x")))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("The database address is not a PostgreSQL JDBC URL");
        assertThat(output.toString()).doesNotContain("secret-host");
    }
}

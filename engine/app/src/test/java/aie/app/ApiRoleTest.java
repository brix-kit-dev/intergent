package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import aie.adapters.storepg.PgPool;
import aie.adapters.storepg.PgRuntime;
import aie.adapters.storepg.TestDatabase;
import aie.core.gateway.api.Gateway;
import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.Provisioning;
import aie.core.tenancy.api.Provisioning.IssuedKey;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.core.tenancy.api.SessionTokens.Session;
import aie.core.tenancy.internal.DatabaseProvisioning;
import aie.core.tenancy.internal.SignedSessionTokens;
import aie.http.api.model.Conversation;
import aie.http.api.model.CreatedApiKey;
import aie.http.api.model.Message;
import aie.http.api.model.MessageStartedEvent;
import aie.http.api.model.Usage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
        context = ApiRole.start("--server.port=0", address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING);
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

    private static HttpResponse<String> onBehalfOf(HttpRequest.Builder request, String key, String lower) throws Exception {
        return HttpClient.newHttpClient().send(
                request.header("Authorization", "Bearer " + key).header("On-Behalf-Of", lower).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder at(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
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
                            "--role=api", "--server.port=" + unused, address(), password(TestDatabase.APP_PASSWORD),
                            TestApi.SESSION_KEY_SETTING}))
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

    /** The api's gateway has the routes of {@link Models}, so the fake model answers customer_chat (M2-2). */
    @Test
    void theApiAnswersCustomerChatThroughItsGateway() {
        List<String> pieces = new ArrayList<>();

        Gateway.Result result = context.getBean(Gateway.class).answer(new Gateway.Request(
                Models.CUSTOMER_CHAT, List.of(new Gateway.Turn(Gateway.Role.USER, "Hello")), () -> false), pieces::add);

        assertThat(result).isInstanceOf(Gateway.Completed.class);
        assertThat(pieces).hasSize(8).startsWith("Piece 1 of 8. ");
    }

    /** A-14: the api's gateway asks the api's own pool, so a call from inside one of its transactions gets no answer. */
    @Test
    void theApiGatewayRefusesACallFromInsideATransaction() {
        PgPool pool = context.getBean(PgPool.class);
        Gateway gateway = context.getBean(Gateway.class);
        List<String> pieces = new ArrayList<>();

        assertThatThrownBy(() -> pool.inTenantTx("ten_a", tx -> gateway.answer(new Gateway.Request(
                        Models.CUSTOMER_CHAT, List.of(new Gateway.Turn(Gateway.Role.USER, "Hello")), () -> false), pieces::add)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("(A-14)");
        assertThat(pieces).isEmpty();
    }

    @Test
    void stoppingTheApiClosesItsConnections() {
        awaitOnlyTheClassPool();
        ConfigurableApplicationContext second =
                ApiRole.start("--server.port=0", address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING);
        await().atMost(SETTLES).until(() -> engineAppHolds(2L * ApiRole.POOL_SIZE));

        second.close();

        awaitOnlyTheClassPool();
    }

    /** The port fails as the context starts, after it has made its beans, so the context closes the pool. */
    @Test
    void anApiThatCannotStartClosesItsPool() throws IOException {
        awaitOnlyTheClassPool();
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            assertThatThrownBy(() -> ApiRole.start(
                            "--server.port=" + taken.getLocalPort(), address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING))
                    .hasStackTraceContaining("already in use");
        }

        awaitOnlyTheClassPool();
    }

    /** A bad setting fails the context before it has made its beans, so only the role can close the pool. */
    @Test
    void anApiWithABadSettingClosesItsPool() {
        awaitOnlyTheClassPool();

        assertThatThrownBy(() -> ApiRole.start("--server.port=not-a-port", address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING))
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

    /** A key created with an expiry works until then and gets 401 from that moment on. */
    @Test
    void aKeyStopsWorkingWhenItExpires() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String first = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String expiry = java.time.Instant.now().plusSeconds(3).toString();

        HttpResponse<String> created = createKey(first, "expiring-1", "{\"expires_at\":\"" + expiry + "\"}");
        String expiring = JSON.readValue(created.body(), CreatedApiKey.class).getKey();

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(status("/v1/nothing-here", expiring)).isEqualTo(404);
        await().atMost(SETTLES).until(() -> status("/v1/nothing-here", expiring) == 401);
        assertThat(status("/v1/nothing-here", first)).isEqualTo(404);
    }

    /** On-Behalf-Of on the real database: the platform's key works as its lower tenant's application, and nowhere else. */
    @Test
    void aPlatformActsForItsLowerTenant() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String platform = provisioning.createTenant("jp1", null);
        IssuedKey platformKey = provisioning.createKey(platform, provisioning.createApplication(platform, "test", "internal"));
        String lower = provisioning.createTenant("jp1", platform);
        String lowerApplication = provisioning.createApplication(lower, "test", "internal");
        String unrelated = provisioning.createTenant("jp1", null);
        provisioning.createApplication(unrelated, "test", "internal");

        HttpResponse<String> seen = onBehalfOf(at("/v1/test/caller"), platformKey.key(), lower);
        HttpResponse<String> created = onBehalfOf(at("/v1/api-keys").header("Idempotency-Key", "for-lower-1")
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{\"name\":\"lower\"}")), platformKey.key(), lower);
        String lowerKey = JSON.readValue(created.body(), CreatedApiKey.class).getKey();
        HttpResponse<String> seenWithTheNewKey = HttpClient.newHttpClient().send(
                at("/v1/test/caller").header("Authorization", "Bearer " + lowerKey).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(seen.body()).contains("\"keyId\":\"" + platformKey.keyId() + "\"")
                .contains("\"tenantId\":\"" + lower + "\"").contains("\"applicationId\":\"" + lowerApplication + "\"");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(seenWithTheNewKey.body()).contains("\"tenantId\":\"" + lower + "\"").contains("\"applicationId\":\"" + lowerApplication + "\"");
        assertThat(TestDatabase.firstColumn(DATABASE,
                        "SELECT tenant_id || ' ' || application_id FROM engine.tenancy_idempotency_keys WHERE idempotency_key = 'for-lower-1'"))
                .containsExactly(lower + " " + lowerApplication);
        assertThat(onBehalfOf(at("/v1/test/caller"), platformKey.key(), unrelated).statusCode()).isEqualTo(403);
    }

    private static HttpResponse<String> startConversation(String key, String idempotencyKey, String body) throws Exception {
        return HttpClient.newHttpClient().send(at("/v1/conversations").header("Authorization", "Bearer " + key)
                .header("Idempotency-Key", idempotencyKey).header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * POST /v1/conversations on the real database (M2-4-2): tenancy makes the end user once, the conversation module
     * keeps the conversation, and a retry with the same Idempotency-Key gets the same conversation, not a second one.
     */
    @Test
    void aConversationStartsForTheHostsUserAndARetryGetsTheSameOne() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String application = provisioning.createApplication(tenant, "test", "internal");
        String key = provisioning.createKey(tenant, application).key();
        String body = "{\"external_user_id\":\"host-user-1\",\"locale\":\"ja-jp\"}";

        HttpResponse<String> started = startConversation(key, "start-1", body);
        HttpResponse<String> retry = startConversation(key, "start-1", body);
        HttpResponse<String> another = startConversation(key, "start-2", body);

        assertThat(started.statusCode()).isEqualTo(201);
        Conversation first = JSON.readValue(started.body(), Conversation.class);
        assertThat(first.getLocale()).isEqualTo("ja-JP");
        assertThat(retry.statusCode()).isEqualTo(201);
        assertThat(retry.body()).isEqualTo(started.body());
        Conversation second = JSON.readValue(another.body(), Conversation.class);
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getEndUserId()).isEqualTo(first.getEndUserId());
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT conversation_id || ' ' || application_id || ' ' || end_user_id"
                        + " || ' ' || locale FROM engine.conversation_conversations WHERE tenant_id = '" + tenant + "' ORDER BY created_at"))
                .containsExactly(first.getId() + " " + application + " " + first.getEndUserId() + " ja-JP",
                        second.getId() + " " + application + " " + first.getEndUserId() + " ja-JP");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT end_user_id || ' ' || external_id FROM engine.tenancy_end_users"
                        + " WHERE tenant_id = '" + tenant + "'"))
                .containsExactly(first.getEndUserId() + " host-user-1");
    }

    /** On-Behalf-Of on the real database: the end user and the conversation belong to the lower tenant's application. */
    @Test
    void aPlatformStartsAConversationForItsLowerTenant() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String platform = provisioning.createTenant("jp1", null);
        IssuedKey platformKey = provisioning.createKey(platform, provisioning.createApplication(platform, "test", "internal"));
        String lower = provisioning.createTenant("jp1", platform);
        String lowerApplication = provisioning.createApplication(lower, "test", "internal");

        HttpResponse<String> started = onBehalfOf(at("/v1/conversations").header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"external_user_id\":\"host-user-1\"}")), platformKey.key(), lower);

        assertThat(started.statusCode()).isEqualTo(201);
        Conversation conversation = JSON.readValue(started.body(), Conversation.class);
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT tenant_id || ' ' || application_id FROM engine.conversation_conversations"
                        + " WHERE conversation_id = '" + conversation.getId() + "'"))
                .containsExactly(lower + " " + lowerApplication);
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT tenant_id || ' ' || application_id FROM engine.tenancy_end_users"
                        + " WHERE end_user_id = '" + conversation.getEndUserId() + "'"))
                .containsExactly(lower + " " + lowerApplication);
    }

    private static HttpResponse<String> readConversation(String key, String id) throws Exception {
        return HttpClient.newHttpClient().send(at("/v1/conversations/" + id).header("Authorization", "Bearer " + key).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * GET /v1/conversations/{id} on the real database (M2-4-3): a conversation reads back as it was started, but only
     * with a key of its own application. The tenant's live application, another tenant and an unknown ID all get the
     * same 404.
     */
    @Test
    void aConversationIsReadOnlyThroughItsOwnApplication() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String testKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String liveKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "live", "internal")).key();
        String stranger = provisioning.createTenant("jp1", null);
        String strangerKey = provisioning.createKey(stranger, provisioning.createApplication(stranger, "test", "internal")).key();
        HttpResponse<String> started = startConversation(testKey, "read-1", "{\"external_user_id\":\"host-user-1\",\"locale\":\"ja-jp\"}");
        String id = JSON.readValue(started.body(), Conversation.class).getId();

        HttpResponse<String> read = readConversation(testKey, id);
        HttpResponse<String> unknown = readConversation(testKey, "conv_" + "0".repeat(26));

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(started.body());
        assertThat(unknown.statusCode()).isEqualTo(404);
        for (String otherKey : List.of(liveKey, strangerKey)) {
            HttpResponse<String> other = readConversation(otherKey, id);
            assertThat(other.statusCode()).isEqualTo(404);
            assertThat(other.body()).isEqualTo(unknown.body());
        }
    }

    /**
     * The role's own generator and the fake model answer a message, and a retry with the same Idempotency-Key replays the
     * answer instead of asking again. Another application's key does not reach the conversation.
     */
    @Test
    void aMessageIsAnsweredWholeAndARetryReplaysIt() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String testKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String liveKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "live", "internal")).key();
        String id = JSON.readValue(startConversation(testKey, "message-1", "{\"external_user_id\":\"host-user-1\"}").body(),
                Conversation.class).getId();
        String question = "[fake pieces=2 delay=1ms] Hi";

        HttpResponse<String> sent = sendMessage(testKey, "message-2", id, "{\"text\":\"" + question + "\"}");
        HttpResponse<String> retry = sendMessage(testKey, "message-2", id, "{\"text\":\"" + question + "\"}");
        HttpResponse<String> elsewhere = sendMessage(liveKey, "message-3", id, "{\"text\":\"Hi\"}");

        assertThat(sent.statusCode()).isEqualTo(200);
        Message answer = JSON.readValue(sent.body(), Message.class);
        assertThat(answer.getStatus()).isEqualTo("completed");
        assertThat(answer.getText()).isEqualTo("Piece 1 of 2. Piece 2 of 2. ");
        assertThat(answer.getUsage()).isEqualTo(new Usage((long) question.length(), 28L));
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).isEqualTo(sent.body());
        assertThat(elsewhere.statusCode()).isEqualTo(404);
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT role || ' ' || status FROM engine.conversation_messages WHERE conversation_id = '"
                + id + "' ORDER BY ordinal")).containsExactly("user completed", "assistant completed");
    }

    /** The role's messages ring the role's signals, which its stream waits on (M2-7-4). */
    @Test
    void theRolesMessagesRingTheRolesSignals() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String application = provisioning.createApplication(tenant, "test", "internal");
        String key = provisioning.createKey(tenant, application).key();
        String id = JSON.readValue(startConversation(key, "signals-1", "{\"external_user_id\":\"host-user-1\"}").body(),
                Conversation.class).getId();
        Owner caller = new Owner("key_unused", tenant, application);
        aie.modules.conversation.api.Messages messages = context.getBean(aie.modules.conversation.api.Messages.class);
        aie.modules.conversation.api.EventSignals signals = context.getBean(aie.modules.conversation.api.EventSignals.class);
        String answer = messages.ask(caller, id, "Hello?").orElseThrow().answer().messageId();

        java.util.concurrent.CompletableFuture<Void> signal = signals.next(answer);
        messages.addDelta(caller, id, answer, "Hi");

        assertThat(signals).isInstanceOf(aie.modules.conversation.internal.LocalEventSignals.class);
        assertThat(signal).isDone();
    }

    /**
     * The controller's stream waits on the very signals the role's messages ring. A stream on other signals would still
     * work, only slower, so no request could tell; the wiring is checked where it is made.
     */
    @Test
    void theRolesStreamWaitsOnTheRolesSignals() throws Exception {
        Object controller = context.getBean(Class.forName("aie.http.ConversationController"));
        java.lang.reflect.Field streamField = controller.getClass().getDeclaredField("stream");
        streamField.setAccessible(true);
        Object stream = streamField.get(controller);
        java.lang.reflect.Field signalsField = stream.getClass().getDeclaredField("signals");
        signalsField.setAccessible(true);

        assertThat(signalsField.get(stream)).isSameAs(context.getBean(aie.modules.conversation.api.EventSignals.class));
    }

    /** The role's own generator, the fake model and the event stream: the answer streams from its log to its ending. */
    @Test
    void aStreamedMessageGoesOutAsItsEvents() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String key = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String id = JSON.readValue(startConversation(key, "stream-1", "{\"external_user_id\":\"host-user-1\"}").body(),
                Conversation.class).getId();

        HttpResponse<String> streamed = HttpClient.newHttpClient().send(at("/v1/conversations/" + id + "/messages")
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(BodyPublishers.ofString("{\"text\":\"[fake pieces=2 delay=50ms] Hi\"}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(streamed.statusCode()).isEqualTo(200);
        assertThat(streamed.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(java.util.Arrays.stream(streamed.body().split("\n\n")).map(event -> event.lines().skip(1).findFirst().orElse("")))
                .containsExactly("event: message.started", "event: message.delta", "event: message.delta", "event: message.completed");
        assertThat(streamed.body()).contains("\"text\":\"Piece 1 of 2. \"", "\"text\":\"Piece 2 of 2. \"");
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT role || ' ' || status FROM engine.conversation_messages WHERE conversation_id = '"
                + id + "' ORDER BY ordinal")).containsExactly("user completed", "assistant completed");
    }

    /** GET .../events of an answer from the api on this port, with Last-Event-ID only when one is given. */
    private static HttpResponse<String> events(int apiPort, String key, String conversationId, String answerId,
            String lastEventId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/v1/conversations/"
                        + conversationId + "/messages/" + answerId + "/events"))
                .header("Authorization", "Bearer " + key).timeout(Duration.ofSeconds(30));
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The events of an SSE body, each without the blank line that ends it. */
    private static List<String> eventsOf(String body) {
        return Arrays.stream(body.split("\n\n")).filter(event -> !event.isEmpty()).toList();
    }

    /** The first events of a stream as they arrive, each without its blank line, then the stream is dropped. */
    private static List<String> firstEvents(InputStream stream, int count) throws IOException {
        List<String> events = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder event = new StringBuilder();
            while (events.size() < count) {
                String line = reader.readLine();
                assertThat(line).as("the stream goes on to event " + (events.size() + 1)).isNotNull();
                if (line.isEmpty()) {
                    events.add(event.toString());
                    event.setLength(0);
                } else {
                    event.append(event.isEmpty() ? "" : "\n").append(line);
                }
            }
        }
        return events;
    }

    /** The line of each event that starts with the prefix, such as its id or its type. */
    private static List<String> lines(List<String> events, String prefix) {
        return events.stream().map(event -> event.lines().filter(line -> line.startsWith(prefix)).findFirst().orElse("")).toList();
    }

    /**
     * Resuming on another instance (M2-8): a streamed answer is dropped after its third event here, and a second api on
     * the same database sends the rest after Last-Event-ID 3, while the answer is still being written here. The two parts
     * hold every event once, in order, and the answer completed although its first caller went away. Once it ended, the
     * second api sends it again whole: the same events.
     */
    @Test
    void aStreamResumesOnAnotherInstanceWithNothingRepeatedOrMissed() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String key = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String id = JSON.readValue(startConversation(key, "resume-1", "{\"external_user_id\":\"host-user-1\"}").body(),
                Conversation.class).getId();
        ConfigurableApplicationContext second =
                ApiRole.start("--server.port=0", address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING);
        try {
            int secondPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
            HttpResponse<InputStream> streaming = HttpClient.newHttpClient().send(at("/v1/conversations/" + id + "/messages")
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(BodyPublishers.ofString("{\"text\":\"[fake pieces=6 delay=300ms] Hi\"}")).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            List<String> first = firstEvents(streaming.body(), 3);
            String answer = JSON.readValue(first.getFirst().lines().filter(line -> line.startsWith("data: ")).findFirst()
                    .orElseThrow().substring("data: ".length()), MessageStartedEvent.class).getMessageId();
            assertThat(TestDatabase.firstColumn(DATABASE, "SELECT status FROM engine.conversation_messages WHERE message_id = '"
                    + answer + "'")).as("the answer is still being written").containsExactly("in_progress");

            HttpResponse<String> rest = events(secondPort, key, id, answer, "3");

            assertThat(rest.statusCode()).isEqualTo(200);
            List<String> whole = new ArrayList<>(first);
            whole.addAll(eventsOf(rest.body()));
            assertThat(lines(whole, "id: ")).containsExactly("id: 1", "id: 2", "id: 3", "id: 4", "id: 5", "id: 6", "id: 7", "id: 8");
            assertThat(lines(whole, "event: ")).containsExactly("event: message.started", "event: message.delta",
                    "event: message.delta", "event: message.delta", "event: message.delta", "event: message.delta",
                    "event: message.delta", "event: message.completed");
            assertThat(String.join("", whole)).contains("Piece 1 of 6. ", "Piece 3 of 6. ", "Piece 6 of 6. ");
            assertThat(TestDatabase.firstColumn(DATABASE, "SELECT status || ' ' || text FROM engine.conversation_messages"
                    + " WHERE message_id = '" + answer + "'")).containsExactly("completed Piece 1 of 6. Piece 2 of 6. Piece 3 of 6."
                    + " Piece 4 of 6. Piece 5 of 6. Piece 6 of 6. ");
            assertThat(events(secondPort, key, id, answer, null).body()).isEqualTo(String.join("\n\n", whole) + "\n\n");
        } finally {
            second.close();
        }
    }

    /**
     * GET .../events on the real database: only a key of the answer's own application reads its events. The tenant's
     * live application, another tenant, a user message and an unknown message all get the same 404. A caller that has
     * the ending gets nothing more, and an id beyond the log is refused.
     */
    @Test
    void anAnswersEventsAreReadOnlyThroughItsOwnApplication() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String testKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String liveKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "live", "internal")).key();
        String stranger = provisioning.createTenant("jp1", null);
        String strangerKey = provisioning.createKey(stranger, provisioning.createApplication(stranger, "test", "internal")).key();
        String id = JSON.readValue(startConversation(testKey, "events-1", "{\"external_user_id\":\"host-user-1\"}").body(),
                Conversation.class).getId();
        String answer = JSON.readValue(sendMessage(testKey, "events-2", id, "{\"text\":\"[fake pieces=2 delay=1ms] Hi\"}").body(),
                Message.class).getId();
        String question = TestDatabase.firstColumn(DATABASE, "SELECT message_id FROM engine.conversation_messages"
                + " WHERE conversation_id = '" + id + "' AND role = 'user'").get(0);

        HttpResponse<String> whole = events(port, testKey, id, answer, null);
        HttpResponse<String> caughtUp = events(port, testKey, id, answer, "4");
        HttpResponse<String> unknown = events(port, testKey, id, "msg_" + "0".repeat(26), null);

        assertThat(whole.statusCode()).isEqualTo(200);
        assertThat(lines(eventsOf(whole.body()), "event: "))
                .containsExactly("event: message.started", "event: message.delta", "event: message.delta", "event: message.completed");
        assertThat(caughtUp.statusCode()).isEqualTo(200);
        assertThat(caughtUp.body()).isEmpty();
        assertThat(events(port, testKey, id, answer, "5").statusCode()).isEqualTo(400);
        assertThat(unknown.statusCode()).isEqualTo(404);
        for (HttpResponse<String> other : List.of(events(port, liveKey, id, answer, null),
                events(port, strangerKey, id, answer, null), events(port, testKey, id, question, null))) {
            assertThat(other.statusCode()).isEqualTo(404);
            assertThat(other.body()).isEqualTo(unknown.body());
        }
    }

    /** POST .../cancel of an answer, to the api on this port, with an Idempotency-Key when one is given. */
    private static HttpResponse<String> cancel(int apiPort, String key, String conversationId, String answerId,
            String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/v1/conversations/"
                        + conversationId + "/messages/" + answerId + "/cancel"))
                .header("Authorization", "Bearer " + key).timeout(Duration.ofSeconds(30));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return HttpClient.newHttpClient().send(request.POST(BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The conversation's answer, once its log holds this many events. */
    private static String answerWithEvents(String conversationId, int count) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < until) {
            List<String> found = TestDatabase.firstColumn(DATABASE, "SELECT m.message_id FROM engine.conversation_messages m"
                    + " WHERE m.conversation_id = '" + conversationId + "' AND m.role = 'assistant' AND (SELECT count(*)"
                    + " FROM engine.conversation_events e WHERE e.message_id = m.message_id) >= " + count);
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("No answer of " + conversationId + " reached " + count + " events");
    }

    /**
     * Cancelling on another instance (M2-9-3): an answer streams here, and a second api on the same database cancels it.
     * The writer here stops at its next piece and ends the answer as cancelled; the cancel waits for that and returns
     * the answer, with its text so far and the usage of every piece the model made, the refused one too. The stream here
     * ends with message.cancelled. A cancel here afterwards finds the answer ended and returns it the same.
     */
    @Test
    void aCancelOnAnotherInstanceStopsTheAnswer() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String key = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String id = JSON.readValue(startConversation(key, "cancel-1", "{\"external_user_id\":\"host-user-1\"}").body(),
                Conversation.class).getId();
        ConfigurableApplicationContext second =
                ApiRole.start("--server.port=0", address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING);
        try {
            int secondPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
            CompletableFuture<HttpResponse<String>> streaming = HttpClient.newHttpClient().sendAsync(
                    at("/v1/conversations/" + id + "/messages").header("Authorization", "Bearer " + key)
                            .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                            .POST(BodyPublishers.ofString("{\"text\":\"[fake pieces=50 delay=200ms] Long\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            String answer = answerWithEvents(id, 3);

            long start = System.nanoTime();
            HttpResponse<String> cancelled = cancel(secondPort, key, id, answer, null);
            Duration took = Duration.ofNanos(System.nanoTime() - start);

            assertThat(cancelled.statusCode()).isEqualTo(200);
            assertThat(took).isLessThan(Duration.ofSeconds(3));
            Message body = JSON.readValue(cancelled.body(), Message.class);
            assertThat(body.getId()).isEqualTo(answer);
            assertThat(body.getStatus()).isEqualTo("cancelled");
            int pieces = body.getText().split("Piece ", -1).length - 1;
            assertThat(pieces).isPositive();
            assertThat(body.getText()).startsWith("Piece 1 of 50. ").endsWith("Piece " + pieces + " of 50. ");
            assertThat(body.getUsage().getOutputTokens())
                    .isEqualTo(body.getText().length() + ("Piece " + (pieces + 1) + " of 50. ").length());
            HttpResponse<String> stream = streaming.get(20, TimeUnit.SECONDS);
            assertThat(lines(eventsOf(stream.body()), "event: ").getLast()).isEqualTo("event: message.cancelled");
            assertThat(stream.body()).contains("\"text\":\"" + body.getText() + "\"");
            assertThat(cancel(port, key, id, answer, null).body()).isEqualTo(cancelled.body());
            assertThat(TestDatabase.firstColumn(DATABASE, "SELECT m.status FROM engine.conversation_messages m"
                    + " JOIN engine.conversation_cancel_requests r ON r.message_id = m.message_id WHERE m.message_id = '"
                    + answer + "'")).containsExactly("cancelled");
        } finally {
            second.close();
        }
    }

    /**
     * POST .../cancel on the real database: only a key of the answer's own application cancels it. The tenant's live
     * application, another tenant, a user message and an unknown message all get the same 404. An answer that ended
     * comes back as it ended, and keeps no request. A retry with the same Idempotency-Key gets the first answer, and the
     * same key on another message is refused, as for any POST.
     */
    @Test
    void anAnswerIsCancelledOnlyThroughItsOwnApplication() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String testKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String liveKey = provisioning.createKey(tenant, provisioning.createApplication(tenant, "live", "internal")).key();
        String stranger = provisioning.createTenant("jp1", null);
        String strangerKey = provisioning.createKey(stranger, provisioning.createApplication(stranger, "test", "internal")).key();
        String id = JSON.readValue(startConversation(testKey, "cancel-2", "{\"external_user_id\":\"host-user-1\"}").body(),
                Conversation.class).getId();
        HttpResponse<String> sent = sendMessage(testKey, "cancel-3", id, "{\"text\":\"[fake pieces=2 delay=1ms] Hi\"}");
        String answer = JSON.readValue(sent.body(), Message.class).getId();
        String question = TestDatabase.firstColumn(DATABASE, "SELECT message_id FROM engine.conversation_messages"
                + " WHERE conversation_id = '" + id + "' AND role = 'user'").get(0);

        HttpResponse<String> unknown = cancel(port, testKey, id, "msg_" + "0".repeat(26), null);
        HttpResponse<String> first = cancel(port, testKey, id, answer, "cancel-4");
        HttpResponse<String> retry = cancel(port, testKey, id, answer, "cancel-4");
        HttpResponse<String> elsewhere = cancel(port, testKey, id, question, "cancel-4");

        assertThat(unknown.statusCode()).isEqualTo(404);
        for (HttpResponse<String> other : List.of(cancel(port, liveKey, id, answer, null),
                cancel(port, strangerKey, id, answer, null), cancel(port, testKey, id, question, null))) {
            assertThat(other.statusCode()).isEqualTo(404);
            assertThat(other.body()).isEqualTo(unknown.body());
        }
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(sent.body());
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).isEqualTo(first.body());
        assertThat(elsewhere.statusCode()).isEqualTo(422);
        assertThat(TestDatabase.firstColumn(DATABASE, "SELECT message_id FROM engine.conversation_cancel_requests"
                + " WHERE message_id = '" + answer + "'")).isEmpty();
    }

    /** A streamed message with an Idempotency-Key, to the api on this port. */
    private static HttpRequest streamedWithKey(int apiPort, String key, String idempotencyKey, String conversationId, String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + apiPort + "/v1/conversations/" + conversationId + "/messages"))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").header("Idempotency-Key", idempotencyKey)
                .timeout(Duration.ofSeconds(30)).POST(BodyPublishers.ofString(body)).build();
    }

    /**
     * Retrying a streamed message (M2-10-2): the answer streams here with an Idempotency-Key, and the same request with the
     * same key comes to a second api on the same database while the answer is still being written. The retry asks
     * nothing: it follows the same answer from its first event to its ending, so both streams hold the same events. Once
     * the answer ended, another retry replays it. The same key asking for JSON, or with another body, is another request.
     */
    @Test
    void aStreamedRetryFollowsTheSameAnswer() throws Exception {
        Provisioning provisioning = new DatabaseProvisioning(PgRuntime.connect(DATABASE.getJdbcUrl(), TestDatabase.APP_PASSWORD));
        String tenant = provisioning.createTenant("jp1", null);
        String key = provisioning.createKey(tenant, provisioning.createApplication(tenant, "test", "internal")).key();
        String id = JSON.readValue(startConversation(key, "retry-1", "{\"external_user_id\":\"host-user-1\"}").body(),
                Conversation.class).getId();
        String body = "{\"text\":\"[fake pieces=6 delay=300ms] Hi\"}";
        ConfigurableApplicationContext second =
                ApiRole.start("--server.port=0", address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING);
        try {
            int secondPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
            CompletableFuture<HttpResponse<String>> first = HttpClient.newHttpClient().sendAsync(
                    streamedWithKey(port, key, "stream-1", id, body), HttpResponse.BodyHandlers.ofString());
            String answer = answerWithEvents(id, 2);

            HttpResponse<String> retry = HttpClient.newHttpClient().send(
                    streamedWithKey(secondPort, key, "stream-1", id, body), HttpResponse.BodyHandlers.ofString());

            HttpResponse<String> original = first.get(30, TimeUnit.SECONDS);
            assertThat(original.statusCode()).isEqualTo(200);
            assertThat(retry.statusCode()).isEqualTo(200);
            assertThat(retry.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("text/event-stream"));
            assertThat(lines(eventsOf(retry.body()), "id: ")).containsExactly(
                    "id: 1", "id: 2", "id: 3", "id: 4", "id: 5", "id: 6", "id: 7", "id: 8");
            assertThat(retry.body()).isEqualTo(original.body());
            assertThat(retry.body()).contains("\"message_id\":\"" + answer + "\"");
            assertThat(TestDatabase.firstColumn(DATABASE, "SELECT role || ' ' || status FROM engine.conversation_messages"
                    + " WHERE conversation_id = '" + id + "' ORDER BY ordinal")).containsExactly("user completed", "assistant completed");

            HttpResponse<String> replay = HttpClient.newHttpClient().send(
                    streamedWithKey(port, key, "stream-1", id, body), HttpResponse.BodyHandlers.ofString());
            assertThat(replay.body()).isEqualTo(original.body());
            assertThat(sendMessage(key, "stream-1", id, body).statusCode()).isEqualTo(422);
            assertThat(HttpClient.newHttpClient().send(streamedWithKey(port, key, "stream-1", id, "{\"text\":\"other\"}"),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(422);
            assertThat(TestDatabase.firstColumn(DATABASE, "SELECT streamed_message_id FROM engine.tenancy_idempotency_keys"
                    + " WHERE idempotency_key = 'stream-1'")).containsExactly(answer);
        } finally {
            second.close();
        }
    }

    private static HttpResponse<String> sendMessage(String key, String idempotencyKey, String conversationId, String body)
            throws Exception {
        HttpRequest request = at("/v1/conversations/" + conversationId + "/messages")
                .header("Authorization", "Bearer " + key)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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
        assertThat(ApiRole.run(address(), password("wrong-secret-value"), TestApi.SESSION_KEY_SETTING)).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("password authentication failed");
        assertThat(output.toString()).doesNotContain("wrong-secret-value");
    }

    @Test
    void aBadAddressIsRefusedWithoutPrintingIt(CapturedOutput output) {
        assertThat(ApiRole.run("--engine.database.url=mysql://secret-host/engine", password("x"), TestApi.SESSION_KEY_SETTING))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("The database address is not a PostgreSQL JDBC URL");
        assertThat(output.toString()).doesNotContain("secret-host");
    }

    private static final Session SESSION =
            new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of("orders.read"), Map.of(), null);

    private static String base64(byte[] key) {
        return Base64.getEncoder().encodeToString(key);
    }

    /** Tokens from the api check out under the key it was given, and under no other. */
    @Test
    void theApiSignsSessionTokensWithItsKey() {
        String token = context.getBean(SessionTokens.class).issue(SESSION).token();

        assertThat(new SignedSessionTokens(Clock.systemUTC(), TestApi.SESSION_KEY, List.of()).verify(token)).isPresent();
        assertThat(new SignedSessionTokens(Clock.systemUTC(), TestApi.sessionKey(8), List.of()).verify(token)).isEmpty();
    }

    /** While the key changes, the api signs with the new key and still accepts the tokens of the old one. */
    @Test
    void anApiWithAPreviousKeyStillAcceptsItsTokens() {
        awaitOnlyTheClassPool();
        byte[] next = TestApi.sessionKey(9);
        String before = context.getBean(SessionTokens.class).issue(SESSION).token();
        ConfigurableApplicationContext rotated = ApiRole.start("--server.port=0", address(), password(TestDatabase.APP_PASSWORD),
                "--engine.session.key=" + base64(next), "--engine.session.previous.key=" + base64(TestApi.SESSION_KEY));
        try {
            SessionTokens tokens = rotated.getBean(SessionTokens.class);

            assertThat(tokens.verify(before)).isPresent();
            assertThat(new SignedSessionTokens(Clock.systemUTC(), next, List.of()).verify(tokens.issue(SESSION).token())).isPresent();
        } finally {
            rotated.close();
        }
        awaitOnlyTheClassPool();
    }

    /** The key is checked before the pool opens: with a wrong password too, the api names the missing key. */
    @Test
    void missingTheSessionKeyIsRefusedBeforeTheDatabase(CapturedOutput output) {
        assertThat(ApiRole.run(address(), password("wrong-secret-value"))).isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Startup refused: ENGINE_SESSION_KEY is required")
                .doesNotContain("password authentication failed");
    }

    @Test
    void aSessionKeyThatIsNotBase64IsRefusedWithoutPrintingIt(CapturedOutput output) {
        assertThat(ApiRole.run(address(), password(TestDatabase.APP_PASSWORD), "--engine.session.key=not*base64*secret"))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr())
                .contains("Startup refused: ENGINE_SESSION_KEY is not base64. Make one with: openssl rand -base64 32");
        assertThat(output.toString()).doesNotContain("not*base64*secret");
    }

    @Test
    void aShortSessionKeyIsRefusedWithoutPrintingIt(CapturedOutput output) {
        String shortKey = base64(TestApi.sessionKey(10, 16));

        assertThat(ApiRole.run(address(), password(TestDatabase.APP_PASSWORD), "--engine.session.key=" + shortKey))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Startup refused: ENGINE_SESSION_KEY needs at least 256 bits, and this one has 128. "
                + "Make one with: openssl rand -base64 32");
        assertThat(output.toString()).doesNotContain(shortKey);
    }

    @Test
    void aShortPreviousKeyIsRefusedByName(CapturedOutput output) {
        assertThat(ApiRole.run(address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING,
                        "--engine.session.previous.key=" + base64(TestApi.sessionKey(11, 31))))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr())
                .contains("Startup refused: ENGINE_SESSION_PREVIOUS_KEY needs at least 256 bits, and this one has 248");
    }

    @Test
    void aPreviousKeyThatIsTheKeyIsRefused(CapturedOutput output) {
        assertThat(ApiRole.run(address(), password(TestDatabase.APP_PASSWORD), TestApi.SESSION_KEY_SETTING,
                        "--engine.session.previous.key=" + base64(TestApi.SESSION_KEY)))
                .isEqualTo(EngineApplication.EXIT_FAILED);

        assertThat(output.getErr()).contains("Startup refused: ENGINE_SESSION_PREVIOUS_KEY is the same as ENGINE_SESSION_KEY");
    }
}

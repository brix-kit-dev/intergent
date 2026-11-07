package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.storepg.TestDatabase;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class ApiRoleTest {

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void createRolesAndStart() throws IOException, InterruptedException {
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
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

package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.Problem;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** No /v1 route exists yet, so a request that passes the check ends in a 404, and one that does not in a 400. */
@ExtendWith(OutputCaptureExtension.class)
class EngineVersionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String HEADER = "Engine-Version";
    private static final String CURRENT = "2026-11-05";
    private static final String PUBLIC_PATH = "/v1/nothing-here";
    private static final Pattern CONTRACT_VERSION = Pattern.compile("(?m)^info:\\n(?:  .*\\n)*?  version: \"([^\"]+)\"$");

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = new SpringApplicationBuilder(ApiRole.class).web(WebApplicationType.SERVLET).run("--server.port=0");
        port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> withVersion(String path, String version) throws Exception {
        return send(request(path).header(HEADER, version));
    }

    private static void assertRefused(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        Problem problem = JSON.readValue(response.body(), Problem.class);
        assertThat(problem.getCode()).isEqualTo("invalid_request");
        assertThat(problem.getDetail()).isEqualTo("Engine-Version must be " + CURRENT);
    }

    @Test
    void theVersionOfTheEngineIsTheVersionOfTheContract() throws IOException {
        Matcher contract = CONTRACT_VERSION.matcher(Files.readString(Path.of("../../contracts/openapi.yaml")));

        assertThat(contract.find()).isTrue();
        assertThat(contract.group(1)).isEqualTo(CURRENT);
    }

    @Test
    void theCurrentVersionPasses() throws Exception {
        assertThat(withVersion(PUBLIC_PATH, CURRENT).statusCode()).isEqualTo(404);
    }

    @Test
    void noVersionPasses() throws Exception {
        assertThat(send(request(PUBLIC_PATH)).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-06", "2020-01-01", "latest", "20261105", "2026-11-05,2026-11-05"})
    void anyOtherVersionIsRefused(String version) throws Exception {
        assertRefused(withVersion(PUBLIC_PATH, version));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1", "/v1/", "/v1/conversations", "/%76%31/nothing-here", "/v1;a=b/nothing-here"})
    void everyPathOfThePublicApiIsChecked(String path) throws Exception {
        assertRefused(withVersion(path, "2020-01-01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/healthz", "/v1x/nothing-here", "/v2/nothing-here", "/nothing-here/v1/"})
    void nothingElseIsChecked(String path) throws Exception {
        assertThat(withVersion(path, "2020-01-01").statusCode()).isNotEqualTo(400);
    }

    @Test
    void twoVersionHeadersAreRefused() throws IOException {
        String twice = HEADER + ": " + CURRENT + "\r\n" + HEADER + ": " + CURRENT + "\r\n";

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + PUBLIC_PATH + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" + twice + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1)).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    void anEmptyVersionIsRefused() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET " + PUBLIC_PATH + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" + HEADER + ":\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1)).startsWith("HTTP/1.1 400");
        }
    }

    @Test
    void aWrongVersionIsNotAFailureOfTheEngine(CapturedOutput output) throws Exception {
        withVersion(PUBLIC_PATH, "2020-01-01");

        assertThat(output.getOut()).doesNotContain("The request failed");
    }
}

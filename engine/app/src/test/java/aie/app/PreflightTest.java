package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.Problem;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The CORS preflight of POST /v1/anonymous-sessions on the real web stack. It needs no credentials, any origin a site
 * key could list passes, and no other path answers a preflight.
 */
class PreflightTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/v1/anonymous-sessions";

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start("--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    /**
     * The HTTP client keeps Origin and the Access-Control headers for itself, so a preflight is written by hand, as a
     * browser sends it. The answer comes back whole, headers and body, with a line break of {@code \n}.
     */
    private static String send(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
        }
    }

    private static String preflight(String path, String origin, String method) throws IOException {
        return send("OPTIONS " + path + " HTTP/1.1\nHost: engine.example\nConnection: close\nOrigin: " + origin
                + "\nAccess-Control-Request-Method: " + method
                + "\nAccess-Control-Request-Headers: content-type,idempotency-key,engine-version\n\n");
    }

    private static String status(String answer) {
        return answer.substring(0, answer.indexOf('\n'));
    }

    /** The value of a header, or null. Header names are matched without regard to case. */
    private static String header(String answer, String name) {
        for (String line : answer.substring(0, answer.indexOf("\n\n")).split("\n")) {
            if (line.toLowerCase().startsWith(name.toLowerCase() + ":")) {
                return line.substring(name.length() + 1).strip();
            }
        }
        return null;
    }

    /** The body, with a chunked one put back together. Its JSON holds no line break, so its sizes still hold. */
    private static String body(String answer) {
        String raw = answer.substring(answer.indexOf("\n\n") + 2);
        if (!"chunked".equalsIgnoreCase(header(answer, "Transfer-Encoding"))) {
            return raw;
        }
        StringBuilder body = new StringBuilder();
        int at = 0;
        while (true) {
            int end = raw.indexOf('\n', at);
            int size = Integer.parseInt(raw.substring(at, end).strip(), 16);
            if (size == 0) {
                return body.toString();
            }
            body.append(raw, end + 1, end + 1 + size);
            at = end + 1 + size + 1;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://shop.example.com", "https://shop.example.com:8443", "http://localhost:3000"})
    void aPreflightFromAnOriginASiteKeyCouldListPasses(String origin) throws IOException {
        String answer = preflight(PATH, origin, "POST");

        assertThat(status(answer)).isEqualTo("HTTP/1.1 204 ");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isEqualTo(origin);
        assertThat(header(answer, "Access-Control-Allow-Methods")).isEqualTo("POST");
        assertThat(header(answer, "Access-Control-Allow-Headers")).isEqualTo("Content-Type, Idempotency-Key, Engine-Version, traceparent");
        assertThat(header(answer, "Access-Control-Max-Age")).isEqualTo("600");
        assertThat(header(answer, "Vary")).isEqualTo("Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
        assertThat(header(answer, "Access-Control-Allow-Credentials")).isNull();
        assertThat(header(answer, "Request-Id")).isNotBlank();
        assertThat(body(answer)).isEmpty();
    }

    /** A page that no site key could list, such as a sandboxed frame with origin null, is refused before any key check. */
    @ParameterizedTest
    @ValueSource(strings = {"http://shop.example.com", "null", "https://*.example.com", "https://shop.example.com/cart", "shop.example.com"})
    void aPreflightFromAnotherOriginIsForbidden(String origin) throws IOException {
        String answer = preflight(PATH, origin, "POST");

        assertThat(status(answer)).isEqualTo("HTTP/1.1 403 ");
        assertThat(header(answer, "Content-Type")).startsWith("application/problem+json");
        assertThat(JSON.readValue(body(answer), Problem.class).getCode()).isEqualTo("forbidden");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PUT", "DELETE", "post"})
    void aPreflightForAnotherMethodIsForbidden(String method) throws IOException {
        String answer = preflight(PATH, "https://shop.example.com", method);

        assertThat(status(answer)).isEqualTo("HTTP/1.1 403 ");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isNull();
    }

    /** Only the anonymous session takes a browser that has no token yet; the rest wait for M2 and still need a key. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/sessions", "/v1/api-keys", "/v1/anonymous-sessions/", "/v1/conversations"})
    void noOtherPathAnswersAPreflight(String path) throws IOException {
        String answer = preflight(path, "https://shop.example.com", "POST");

        assertThat(status(answer)).isEqualTo("HTTP/1.1 401 ");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isNull();
    }

    /** Without Access-Control-Request-Method it is no preflight, so it goes on to the key check like any request. */
    @Test
    void anOptionsRequestThatIsNoPreflightNeedsAKey() throws IOException {
        String answer = send("OPTIONS " + PATH + " HTTP/1.1\nHost: engine.example\nConnection: close\nOrigin: https://shop.example.com\n\n");

        assertThat(status(answer)).isEqualTo("HTTP/1.1 401 ");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isNull();
    }

    /** The POST itself is not answered to any origin yet; 12-5 answers it only to the origins of its site key. */
    @Test
    void thePostItselfGetsNoCorsYet() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .header("Authorization", TestApi.AUTHORIZATION).header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"site_key\":\"x\"}")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }
}

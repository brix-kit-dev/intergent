package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.Problem;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * The CORS preflights on the real web stack: that of POST /v1/anonymous-sessions, and those of the browser's operations
 * (M2-12-4). They need no credentials, and any origin a site key could list passes. A server's path answers none.
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
     * A request written by hand over a socket, so the test sees the answer exactly as the server sends it: the status
     * line, every header, and the body. It comes back whole, with a line break of {@code \n}.
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

    /** A server's path, or one no operation has, answers no preflight: it goes on to the key check, which refuses it. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/sessions", "/v1/api-keys", "/v1/api-keys/key_1", "/v1/anonymous-sessions/", "/v1/conversations/",
        "/v1/conversations/conv_1/messages/msg_1/nothing", "/v1/nothing"})
    void noOtherPathAnswersAPreflight(String path) throws IOException {
        String answer = preflight(path, "https://shop.example.com", "POST");

        assertThat(status(answer)).isEqualTo("HTTP/1.1 401 ");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isNull();
    }

    /** Each path of the browser's operations, with the methods its operations take, in alphabetical order. */
    static List<Arguments> browserPaths() {
        return List.of(
                Arguments.of("/v1/conversations", "POST", "POST"),
                Arguments.of("/v1/conversations/conv_1", "GET", "GET"),
                Arguments.of("/v1/conversations/conv_1/messages", "POST", "GET, POST"),
                Arguments.of("/v1/conversations/conv_1/messages", "GET", "GET, POST"),
                Arguments.of("/v1/conversations/conv_1/messages/msg_1", "GET", "GET"),
                Arguments.of("/v1/conversations/conv_1/messages/msg_1/events", "GET", "GET"),
                Arguments.of("/v1/conversations/conv_1/messages/msg_1/events", "HEAD", "GET"),
                Arguments.of("/v1/conversations/conv_1/messages/msg_1/cancel", "POST", "POST"));
    }

    /**
     * M2-12-4: a browser's operation answers its preflight for any origin a page could have (M2 decision 8), with the
     * methods of its path and the headers the operations read, the token and Last-Event-ID among them. No credentials.
     */
    @ParameterizedTest
    @MethodSource("browserPaths")
    void aPreflightOfABrowsersOperationPasses(String path, String method, String methods) throws IOException {
        String answer = preflight(path, "https://shop.example.com", method);

        assertThat(status(answer)).isEqualTo("HTTP/1.1 204 ");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isEqualTo("https://shop.example.com");
        assertThat(header(answer, "Access-Control-Allow-Methods")).isEqualTo(methods);
        assertThat(header(answer, "Access-Control-Allow-Headers"))
                .isEqualTo("Authorization, Content-Type, Idempotency-Key, Engine-Version, traceparent, Last-Event-ID");
        assertThat(header(answer, "Access-Control-Max-Age")).isEqualTo("600");
        assertThat(header(answer, "Vary")).isEqualTo("Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
        assertThat(header(answer, "Access-Control-Allow-Credentials")).isNull();
        assertThat(body(answer)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://shop.example.com", "null", "https://shop.example.com/chat", "http://127.0.0.2:18301"})
    void aBrowsersPreflightFromAnOriginNoPageCouldHaveIsForbidden(String origin) throws IOException {
        String answer = preflight("/v1/conversations/conv_1/messages", origin, "POST");

        assertThat(status(answer)).isEqualTo("HTTP/1.1 403 ");
        assertThat(JSON.readValue(body(answer), Problem.class).getCode()).isEqualTo("forbidden");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isNull();
    }

    /** A method none of the path's operations takes is refused, such as reading a cancel or changing a conversation. */
    @ParameterizedTest
    @ValueSource(strings = {"/v1/conversations GET", "/v1/conversations/conv_1 POST", "/v1/conversations/conv_1 DELETE",
        "/v1/conversations/conv_1/messages/msg_1/cancel GET", "/v1/conversations/conv_1/messages/msg_1 post"})
    void aBrowsersPreflightForAMethodItsPathDoesNotTakeIsForbidden(String pathAndMethod) throws IOException {
        String[] asked = pathAndMethod.split(" ");

        String answer = preflight(asked[0], "https://shop.example.com", asked[1]);

        assertThat(status(answer)).isEqualTo("HTTP/1.1 403 ");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isNull();
    }

    /** Without Access-Control-Request-Method it is no preflight, so it goes on to the key check like any request. */
    @Test
    void anOptionsRequestThatIsNoPreflightNeedsAKey() throws IOException {
        String answer = send("OPTIONS " + PATH + " HTTP/1.1\nHost: engine.example\nConnection: close\nOrigin: https://shop.example.com\n\n");

        assertThat(status(answer)).isEqualTo("HTTP/1.1 401 ");
        assertThat(header(answer, "Access-Control-Allow-Origin")).isNull();
    }

}

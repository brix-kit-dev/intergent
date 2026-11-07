package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** A caller's trace on the real web stack. The logs are what a host lines up with its own. */
@ExtendWith(OutputCaptureExtension.class)
class TraceparentLogTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String HEADER = "00-" + TRACE + "-00f067aa0ba902b7-01";

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

    private static String lineWith(CapturedOutput output, String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).findFirst().orElseThrow();
    }

    @ParameterizedTest
    @CsvSource({
        "/test/log, a line from inside a request",
        "/test/filter-log, a line from inside a filter",
        "/test/fail, The request failed",
        "/test/filter-fails, The request failed"
    })
    void aLogLineCarriesTheTraceOfItsRequest(String path, String text, CapturedOutput output) throws Exception {
        send(request(path).header("traceparent", HEADER));

        assertThat(lineWith(output, text)).contains("[" + TRACE + "]");
    }

    @Test
    void aRequestWithoutATraceLogsAnEmptyOne(CapturedOutput output) throws Exception {
        send(request("/test/log"));

        assertThat(lineWith(output, "a line from inside a request")).contains("[] ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a traceparent", "ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"})
    void anInvalidTraceDoesNotStopTheRequest(String header, CapturedOutput output) throws Exception {
        HttpResponse<String> response = send(request("/test/log").header("traceparent", header));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(lineWith(output, "a line from inside a request")).doesNotContain(header).contains("[] ");
    }

    @Test
    void theResponseDoesNotRepeatIt() throws Exception {
        assertThat(send(request("/healthz").header("traceparent", HEADER)).headers().firstValue("traceparent")).isEmpty();
    }

    @Test
    void twoTraceHeadersCountAsNone(CapturedOutput output) throws IOException {
        String twice = "traceparent: " + HEADER + "\r\ntraceparent: " + HEADER + "\r\n";

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /test/log HTTP/1.1\r\nHost: x\r\nConnection: close\r\n" + twice + "\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1)).startsWith("HTTP/1.1 200");
        }

        assertThat(lineWith(output, "a line from inside a request")).doesNotContain(TRACE);
    }
}

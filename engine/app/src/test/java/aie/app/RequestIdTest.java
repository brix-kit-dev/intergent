package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs the real web stack over real HTTP. It starts past the self-check, because no database is involved. */
@ExtendWith(OutputCaptureExtension.class)
class RequestIdTest {

    private static final String AN_ENGINE_ID = "req_[0-9A-HJKMNP-TV-Z]{26}";

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

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static String requestId(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Request-Id")).hasSize(1);
        return response.headers().firstValue("Request-Id").orElseThrow();
    }

    @ParameterizedTest
    @CsvSource({
        "GET, /healthz, 200",
        "GET, /nothing-here, 404",
        "POST, /healthz, 405",
        "GET, /test/fail, 500",
        "GET, /test/refused-by-filter, 401"
    })
    void everyResponseCarriesExactlyOneRequestId(String method, String path, int status) throws Exception {
        HttpResponse<String> response = send(request(path).method(method, HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(requestId(response)).matches(AN_ENGINE_ID);
    }

    @Test
    void everyRequestGetsItsOwn() throws Exception {
        assertThat(requestId(send(request("/healthz")))).isNotEqualTo(requestId(send(request("/healthz"))));
    }

    @Test
    void aClientCannotChooseIt() throws Exception {
        assertThat(requestId(send(request("/healthz").header("Request-Id", "req_chosen_by_the_client"))))
                .matches(AN_ENGINE_ID);
    }

    @Test
    void aLogLineFromARequestCarriesItsId(CapturedOutput output) throws Exception {
        String id = requestId(send(request("/test/log")));

        assertThat(output.getOut().lines().filter(line -> line.contains("a line from inside a request")))
                .singleElement().asString().contains("[" + id + "]");
    }
}

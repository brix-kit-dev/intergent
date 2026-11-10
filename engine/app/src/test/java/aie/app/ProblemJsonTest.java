package aie.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import aie.http.api.model.Problem;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** Every way a request can fail, on the real web stack: inside a controller, in a filter, and in Tomcat itself. */
@ExtendWith(OutputCaptureExtension.class)
class ProblemJsonTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBLEM = "application/problem+json";
    private static final String AN_ENGINE_ID = "req_[0-9A-HJKMNP-TV-Z]{26}";
    private static final String CLOSE = "Connection: close\r\n";

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

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static HttpRequest.Builder post(String path, String contentType, String body) {
        return request(path).header("Content-Type", contentType).POST(BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return send(request(path));
    }

    private static String requestId(HttpResponse<String> response) {
        return response.headers().firstValue("Request-Id").orElseThrow();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains(PROBLEM);
        assertThat(requestId(response)).matches(AN_ENGINE_ID);
        assertThat(problem(response.body())).satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(code);
            assertThat(problem.getStatus()).isEqualTo(status);
            assertThat(problem.getType()).isEqualTo("about:blank");
            assertThat(problem.getTitle()).isNotBlank();
        });
    }

    private static Problem problem(String body) {
        return JSON.readValue(body, Problem.class);
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                arguments("an unknown path", request("/nothing-here"), 404, "not_found"),
                arguments("the error page asked for directly", request("/error"), 404, "not_found"),
                arguments("a wrong method", request("/healthz").POST(BodyPublishers.noBody()), 405, "invalid_request"),
                arguments("nothing acceptable", request("/healthz").header("Accept", "application/xml"), 406, "invalid_request"),
                arguments("a wrong media type", post("/test/body", "text/plain", "{}"), 415, "invalid_request"),
                arguments("broken JSON", post("/test/body", "application/json", "{broken"), 400, "invalid_request"),
                arguments("a bad path variable", request("/test/number/abc"), 400, "invalid_request"),
                arguments("a missing parameter", request("/test/param"), 400, "invalid_request"),
                arguments("sendError in a filter", request("/test/send-error"), 400, "invalid_request"),
                arguments("a 500 from a filter", request("/test/send-error-500"), 500, "internal_error"),
                arguments("a failure in a controller", request("/test/fail"), 500, "internal_error"),
                arguments("a failure in a filter", request("/test/filter-fails"), 500, "internal_error"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void everyRefusalIsAProblem(String name, HttpRequest.Builder request, int status, String code) throws Exception {
        assertProblem(send(request), status, code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/nothing-here", "/test/fail", "/test/filter-fails", "/test/send-error"})
    void aBrowserGetsAProblemToo(String path) throws Exception {
        HttpResponse<String> response = send(request(path).header("Accept", "text/html"));

        assertThat(response.headers().firstValue("Content-Type")).contains(PROBLEM);
        assertThat(response.body()).doesNotContain("<html");
    }

    @Test
    void aWrongMethodSaysWhichOnesWork() throws Exception {
        assertThat(send(request("/healthz").POST(BodyPublishers.noBody())).headers().firstValue("Allow")).hasValueSatisfying(
                allow -> assertThat(allow).contains("GET"));
    }

    @Test
    void aBadRequestSaysWhatIsWrong() throws Exception {
        assertThat(problem(get("/test/param").body()).getDetail()).contains("needed");
    }

    @Test
    void brokenJsonGivesAwayNothingOfTheParser() throws Exception {
        String body = send(post("/test/body", "application/json", "{broken")).body();

        assertThat(problem(body).getDetail()).isEqualTo("The body is not valid JSON");
        assertThat(body).doesNotContain("broken", "jackson", "Unexpected", "line:");
    }

    @Test
    void aMissingBodyIsNamed() throws Exception {
        HttpResponse<String> response = send(post("/test/body", "application/json", ""));

        assertProblem(response, 400, "invalid_request");
        assertThat(problem(response.body()).getDetail()).isEqualTo("The request needs a JSON body");
        assertThat(response.body()).doesNotContain("Required", "public", "aie.");
    }

    /** The probe reads a map of strings, whose keys the caller chose, so the detail leaves the key out. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            []                      | The body must be a single JSON object
            {"chosen_key":{"x":1}}  | each value in the body must be a string
            """)
    void aBodyOfTheWrongShapeSaysWhatItMustBe(String json, String detail) throws Exception {
        HttpResponse<String> response = send(post("/test/body", "application/json", json));

        assertProblem(response, 400, "invalid_request");
        assertThat(problem(response.body()).getDetail()).isEqualTo(detail);
        assertThat(response.body()).doesNotContain("chosen_key", "java.", "LinkedHashMap");
    }

    @Test
    void aMissingPathNamesNothingInside() throws Exception {
        assertThat(problem(get("/nothing-here").body()).getDetail()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test/fail", "/test/filter-fails", "/test/send-error-500"})
    void aFailureTellsTheCallerNothingAboutTheEngine(String path) throws Exception {
        String body = get(path).body();

        assertThat(problem(body).getDetail()).isNull();
        assertThat(body).doesNotContain("failure inside", "internal message", "IllegalState", "Exception", "aie.", "trace");
    }

    @Test
    void aRefusalWithNoReasonHasNoDetail() throws Exception {
        assertThat(problem(get("/test/send-error").body()).getDetail()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/test/fail", "/test/filter-fails"})
    void aFailureIsLoggedWithItsRequestIdAndCause(String path, CapturedOutput output) throws Exception {
        String id = requestId(get(path));

        assertThat(output.getOut().lines().filter(line -> line.contains("The request failed") && line.contains("[" + id + "]")))
                .hasSize(1);
        assertThat(output.getOut()).contains("java.lang.IllegalStateException: a failure inside a");
    }

    /** Spring reports this one with its own exception and a detail of its own, which must not reach the caller. */
    @Test
    void aServerSideFailureOfSpringIsHandledLikeAnyOther(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/test/unwritable");

        assertProblem(response, 500, "internal_error");
        assertThat(problem(response.body()).getDetail()).isNull();
        assertThat(output.getOut().lines().filter(line -> line.contains("The request failed") && line.contains(requestId(response))))
                .hasSize(1);
    }

    /** Tomcat logs what reaches it, without the Request-Id, so a failure in a controller must stop before that. */
    @Test
    void aControllerFailureIsLoggedOnlyOnce(CapturedOutput output) throws Exception {
        get("/test/fail");

        assertThat(output.getOut()).doesNotContain("Servlet.service()");
    }

    @Test
    void aRefusalIsNotLoggedAsAFailure(CapturedOutput output) throws Exception {
        get("/nothing-here");
        get("/test/param");

        assertThat(output.getOut()).doesNotContain("The request failed");
    }

    private static String raw(String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String header(String response, String name) {
        return Arrays.stream(response.split("\r\n\r\n", 2)[0].split("\r\n"))
                .filter(line -> line.toLowerCase().startsWith(name.toLowerCase() + ":"))
                .map(line -> line.substring(name.length() + 1).trim())
                .findFirst().orElse("");
    }

    private static String get(String target, String headers) {
        return "GET " + target + " HTTP/1.1\r\n" + headers + CLOSE + "\r\n";
    }

    static Stream<Arguments> whatTomcatRefuses() {
        String host = "Host: x\r\n";
        return Stream.of(
                arguments("a bad percent escape", get("/%zz", host)),
                arguments("a control character", get("/a\u0001b", host)),
                arguments("an encoded slash", get("/a%2fb", host)),
                arguments("a path above the root", get("/../../etc/passwd", host)),
                arguments("no Host header", get("/healthz", "")),
                arguments("a huge header", get("/healthz", host + "X-Big: " + "a".repeat(20_000) + "\r\n")),
                arguments("a bad request line", "NONSENSE\r\n\r\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("whatTomcatRefuses")
    void whatTomcatRefusesIsAProblemToo(String name, String request) throws Exception {
        String response = raw(request);

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(header(response, "Content-Type")).isEqualTo(PROBLEM);
        assertThat(header(response, "Request-Id")).matches(AN_ENGINE_ID);
        assertThat(problem(response.split("\r\n\r\n", 2)[1])).satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo("invalid_request");
            assertThat(problem.getStatus()).isEqualTo(400);
        });
        assertThat(response).doesNotContain("Tomcat", "<html");
    }
}

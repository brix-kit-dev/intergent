package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.metering.api.UsageReports;
import aie.http.api.model.Problem;
import aie.http.api.model.UsageReport;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET /v1/usage (M3-6-4) on the real web stack, with stand-in reports that answer what each test sets.
 * DatabaseUsageReportsTest sums the real usage, and ApiRoleTest reads it through the real api.
 */
@Timeout(30)
class UsageControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TIME_RULE = " must be an RFC 3339 date-time with its seconds and its offset, such as"
            + " 2026-11-01T00:00:00+09:00";

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

    @BeforeEach
    void forgetEarlierAsks() {
        TestApi.USAGE.reset();
    }

    /** GET /v1/usage with this query, with the test's server key and any further headers, in pairs. */
    private static HttpResponse<String> usage(String query, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/usage" + query))
                .header("Authorization", TestApi.AUTHORIZATION).timeout(Duration.ofSeconds(20));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Problem problem(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        return JSON.readValue(response.body(), Problem.class);
    }

    /**
     * M3-6-4: the report of the key's own application, as one JSON object: the window written in UTC, the totals, and each model,
     * its fields as the contract names them. A cost is an exact decimal in a string with its currency, with six places at
     * least and none taken away, so nothing is rounded.
     */
    @Test
    void theReportOfTheKeysApplicationComesAsTheContractWritesIt() throws Exception {
        TestApi.USAGE.next = new UsageReports.Report(Instant.parse("2026-10-31T15:00:00Z"), Instant.parse("2026-11-30T15:00:00Z"),
                1239, 579, new BigDecimal("0.012207375"), List.of(
                        new UsageReports.ModelUse("cheap", 5, 12, new BigDecimal("0.000000375")),
                        new UsageReports.ModelUse("dear", 1234, 567, new BigDecimal("0.012207")),
                        new UsageReports.ModelUse("free", 0, 0, BigDecimal.ZERO)));

        HttpResponse<String> response = usage("");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        Map<String, Object> body = JSON.readValue(response.body(), new TypeReference<LinkedHashMap<String, Object>>() {
        });
        assertThat(body.keySet()).containsExactlyInAnyOrder("from", "to", "input_tokens", "output_tokens", "cost", "by_model");
        assertThat(body).containsEntry("from", "2026-10-31T15:00:00Z").containsEntry("to", "2026-11-30T15:00:00Z");
        UsageReport report = JSON.readValue(response.body(), UsageReport.class);
        assertThat(report.getFrom()).isEqualTo(OffsetDateTime.parse("2026-10-31T15:00:00Z"));
        assertThat(report.getTo()).isEqualTo(OffsetDateTime.parse("2026-11-30T15:00:00Z"));
        assertThat(report.getInputTokens()).isEqualTo(1239);
        assertThat(report.getOutputTokens()).isEqualTo(579);
        assertThat(report.getCost().getAmount()).isEqualTo("0.012207375");
        assertThat(report.getCost().getCurrency()).isEqualTo("USD");
        assertThat(report.getByModel()).extracting(use -> use.getModel() + " " + use.getInputTokens() + " " + use.getOutputTokens()
                + " " + use.getCost().getAmount() + " " + use.getCost().getCurrency())
                .containsExactly("cheap 5 12 0.000000375 USD", "dear 1234 567 0.012207 USD", "free 0 0 0.000000 USD");
        assertThat(TestApi.USAGE.asked).containsExactly("ten_test/app_test null null");
    }

    /** M3-6-4: the window's times come to the reports as the moments they name, in any offset, and go back written in UTC. */
    @Test
    void theWindowsTimesAreTheMomentsTheyName() throws Exception {
        HttpResponse<String> response = usage("?from=" + encoded("2026-11-01T00:00:00+09:00") + "&to=" + encoded("2026-11-15t12:30:00.5z"));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"from\":\"2026-10-31T15:00:00Z\"", "\"to\":\"2026-11-15T12:30:00.5Z\"");
        UsageReport report = JSON.readValue(response.body(), UsageReport.class);
        assertThat(report.getFrom()).isEqualTo(OffsetDateTime.parse("2026-10-31T15:00:00Z"));
        assertThat(report.getTo()).isEqualTo(OffsetDateTime.parse("2026-11-15T12:30:00.5Z"));
        assertThat(report.getCost().getAmount()).isEqualTo("0.000000");
        assertThat(report.getByModel()).isEmpty();
        assertThat(TestApi.USAGE.asked).containsExactly("ten_test/app_test 2026-10-31T15:00:00Z 2026-11-15T12:30:00.500Z");
    }

    /**
     * Point 46 and M3-6-4: a time that is not RFC 3339, with its seconds and its offset, is refused before anything is read,
     * and its detail does not repeat it.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2026-11-01", "2026-11-01T00:00Z", "2026-11-01T00:00:00", "2026-11-01 00:00:00Z", "2026-02-30T00:00:00Z",
        "1763683200", "2026-11-01T00:00:00+0900", "", " 2026-11-01T00:00:00Z", "2026-11-01T00:00:00Z\u0000"})
    void aTimeThatIsNotRfc3339IsRefused(String time) throws Exception {
        Problem from = problem(usage("?from=" + encoded(time)), 400);
        Problem to = problem(usage("?to=" + encoded(time)), 400);

        assertThat(from.getCode()).isEqualTo("invalid_request");
        assertThat(from.getDetail()).isEqualTo("from" + TIME_RULE);
        assertThat(to.getDetail()).isEqualTo("to" + TIME_RULE);
        assertThat(TestApi.USAGE.asked).isEmpty();
    }

    /** M3-6-4: a window that does not end after it starts is refused, as the reports refuse it. */
    @Test
    void aWindowThatDoesNotEndAfterItStartsIsRefused() throws Exception {
        Problem same = problem(usage("?from=2026-11-01T00:00:00Z&to=2026-11-01T00:00:00Z"), 400);
        Problem backwards = problem(usage("?from=2026-11-02T00:00:00Z&to=" + encoded("2026-11-02T08:59:59+09:00")), 400);

        assertThat(same.getCode()).isEqualTo("invalid_request");
        assertThat(same.getDetail()).isEqualTo("from must come before to");
        assertThat(backwards.getDetail()).isEqualTo("from must come before to");
    }

    /** The contract allows each time 64 characters at most. */
    @Test
    void aTimeOfMoreThan64CharactersIsRefused() throws Exception {
        assertThat(problem(usage("?from=2026-11-01T00:00:00." + "0".repeat(60) + "Z"), 400).getCode()).isEqualTo("invalid_request");
        assertThat(TestApi.USAGE.asked).isEmpty();
    }

    /** Blueprint 6.5: with On-Behalf-Of the report is of the lower tenant's application the key acts as. */
    @Test
    void onBehalfOfReportsTheLowerTenantsApplication() throws Exception {
        assertThat(usage("", "On-Behalf-Of", "ten_lower").statusCode()).isEqualTo(200);

        assertThat(TestApi.USAGE.asked).containsExactly("ten_lower/app_lowertest null null");
    }

    /** S-10 and M3-6 decision 6: a browser's token reads no usage, and neither does a request without a key. */
    @Test
    void onlyAServerKeyReadsUsage() throws Exception {
        String token = TestApi.sessionToken("eu_test");

        assertThat(problem(usage("", "Authorization", "Bearer " + token), 403).getCode()).isEqualTo("forbidden");
        assertThat(problem(usage("", "Authorization", "Bearer aie_test_dev_" + "9".repeat(40)), 401).getCode()).isEqualTo("unauthorized");
        assertThat(TestApi.USAGE.asked).isEmpty();
    }
}

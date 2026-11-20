package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.trust.api.DataClass;
import aie.http.StreamLimit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The least data class of a call (blueprint 7.3, M3-3-1), on the real web stack with stand-ins: the application's
 * default, which the data_class a caller declares can raise and never lower, as the contract says. The stand-in answers
 * keep each call's floor; the real storage raises it further to the conversation's watermark, which DataClassesTest
 * checks.
 */
class MessageDataClassTest {

    private static final TestApi.StandInApplications APPLICATIONS = new TestApi.StandInApplications();
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final TestApi.StandInAnswers ANSWERS = new TestApi.StandInAnswers();
    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), new TestApi.StandInEndUsers(), CONVERSATIONS,
                ANSWERS, TestApi.NO_REPLAYS, StreamLimit.DEFAULT, TestApi.WAIT, APPLICATIONS, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    /** Each test sees only the calls it made itself, and starts at public. */
    @BeforeEach
    void forgetEarlierCalls() {
        APPLICATIONS.calls.clear();
        APPLICATIONS.defaultClass = DataClass.PUBLIC;
        ANSWERS.calls.clear();
        ANSWERS.floors.clear();
    }

    /** POST .../messages with this credential and JSON, and any further headers, in pairs. */
    private static HttpResponse<String> send(String authorization, String conversationId, String body, String... headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v1/conversations/" + conversationId + "/messages"))
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30)).POST(BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.setHeader(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Without data_class, a call takes its application's default, which is read for each call. */
    @ParameterizedTest
    @EnumSource(DataClass.class)
    void aCallWithoutAClassTakesTheApplicationsDefault(DataClass defaultClass) throws Exception {
        APPLICATIONS.defaultClass = defaultClass;

        HttpResponse<String> response = send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"Hi\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ANSWERS.floors).containsExactly(defaultClass);
        assertThat(APPLICATIONS.calls).containsExactly("ten_test/app_test reads its default data class");
    }

    @ParameterizedTest(name = "default {0}, declared {1}: {2}")
    @CsvSource({
        "public, public, public", "public, internal, internal", "public, personal, personal", "public, sensitive, sensitive",
        "internal, public, internal", "internal, internal, internal", "internal, personal, personal",
        "internal, sensitive, sensitive",
        "personal, public, personal", "personal, internal, personal", "personal, personal, personal",
        "personal, sensitive, sensitive",
        "sensitive, public, sensitive", "sensitive, internal, sensitive", "sensitive, personal, sensitive",
        "sensitive, sensitive, sensitive"})
    void aDeclaredClassCanOnlyRaiseTheDefault(String defaultClass, String declared, String counted) throws Exception {
        APPLICATIONS.defaultClass = DataClass.fromWireName(defaultClass).orElseThrow();

        HttpResponse<String> response = send(TestApi.AUTHORIZATION, "conv_1",
                "{\"text\":\"Hi\",\"data_class\":\"" + declared + "\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ANSWERS.floors).containsExactly(DataClass.fromWireName(counted).orElseThrow());
    }

    @Test
    void aStreamTakesTheSameClass() throws Exception {
        APPLICATIONS.defaultClass = DataClass.INTERNAL;

        HttpResponse<String> raised = send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"Hi\",\"data_class\":\"personal\"}",
                "Accept", "text/event-stream");
        HttpResponse<String> kept = send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"Hi\",\"data_class\":\"public\"}",
                "Accept", "text/event-stream");

        assertThat(raised.body()).startsWith("id: 1\nevent: message.started\n");
        assertThat(kept.body()).startsWith("id: 1\nevent: message.started\n");
        assertThat(ANSWERS.floors).containsExactly(DataClass.PERSONAL, DataClass.INTERNAL);
    }

    /** A browser's call takes the default of the application its token was issued for. */
    @Test
    void aBrowsersCallTakesItsApplicationsDefault() throws Exception {
        APPLICATIONS.defaultClass = DataClass.PERSONAL;
        String conversation = CONVERSATIONS.start(TestApi.OWNER, "eu_1", null).conversationId();

        HttpResponse<String> response = send("Bearer " + TestApi.sessionToken("eu_1"), conversation, "{\"text\":\"Hi\"}");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ANSWERS.floors).containsExactly(DataClass.PERSONAL);
        assertThat(APPLICATIONS.calls).containsExactly("ten_test/app_test reads its default data class");
    }

    /** A class outside the contract is refused before the default is read or anything is asked. */
    @Test
    void anUnknownClassReadsAndAsksNothing() throws Exception {
        HttpResponse<String> response = send(TestApi.AUTHORIZATION, "conv_1", "{\"text\":\"Hi\",\"data_class\":\"secret\"}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(APPLICATIONS.calls).isEmpty();
        assertThat(ANSWERS.calls).isEmpty();
    }
}

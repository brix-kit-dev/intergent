package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.http.api.model.Conversation;
import aie.http.api.model.Problem;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** POST /v1/conversations on the real web stack, with stand-ins for the tenancy and conversation modules. */
class ConversationControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInEndUsers END_USERS = new TestApi.StandInEndUsers();
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final String NO_USER = "external_user_id is required with a server key";
    private static final String USER_RULE = "external_user_id must have 1 to 255 characters";
    private static final String LOCALE_RULE = "locale must be a BCP 47 language tag of at most 35 characters, such as ja-JP";

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), END_USERS, CONVERSATIONS, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void forgetCalls() {
        END_USERS.calls.clear();
        CONVERSATIONS.calls.clear();
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> start(String body, String... headers) throws Exception {
        HttpRequest.Builder request = request("/v1/conversations").header("Authorization", TestApi.AUTHORIZATION)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return send(request);
    }

    /** A body written by Jackson, so that every string is escaped properly. */
    private static String body(Map<String, Object> fields) {
        return JSON.writeValueAsString(fields);
    }

    private static Conversation started(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(201);
        return JSON.readValue(response.body(), Conversation.class);
    }

    private static void assertRefused(HttpResponse<String> response, String detail) {
        assertProblem(response, 400, "invalid_request");
        assertThat(JSON.readValue(response.body(), Problem.class).getDetail()).isEqualTo(detail);
        assertNothingMade();
    }

    private static void assertNothingMade() {
        assertThat(END_USERS.calls).as("a refused request makes no end user").isEmpty();
        assertThat(CONVERSATIONS.calls).as("a refused request starts no conversation").isEmpty();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(JSON.readValue(response.body(), Problem.class).getCode()).isEqualTo(code);
    }

    @Test
    void aConversationStartsForTheHostsUser() throws Exception {
        HttpResponse<String> response = start("{\"external_user_id\":\"host-user-1\",\"locale\":\"ja-JP\"}");

        Conversation conversation = started(response);
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        assertThat(conversation.getId()).startsWith("conv_stand");
        assertThat(conversation.getEndUserId()).startsWith("eu_stand");
        assertThat(conversation.getLocale()).isEqualTo("ja-JP");
        assertThat(response.body()).contains("\"created_at\":\"2026-11-13T00:00:00Z\"");
        assertThat(END_USERS.calls).containsExactly("ten_test/app_test resolves host-user-1");
        assertThat(CONVERSATIONS.calls).containsExactly("ten_test/app_test starts for " + conversation.getEndUserId() + " in ja-JP");
    }

    @Test
    void theLocaleIsKeptInItsStandardForm() throws Exception {
        Conversation conversation = started(start("{\"external_user_id\":\"host-user-1\",\"locale\":\"ja-jp\"}"));

        assertThat(conversation.getLocale()).isEqualTo("ja-JP");
        assertThat(CONVERSATIONS.calls).singleElement().asString().endsWith(" in ja-JP");
    }

    @Test
    void aConversationNeedsNoLocale() throws Exception {
        HttpResponse<String> response = start("{\"external_user_id\":\"host-user-1\"}");

        assertThat(started(response).getLocale()).isNull();
        assertThat(response.body()).doesNotContain("locale");
        assertThat(CONVERSATIONS.calls).singleElement().asString().endsWith(" in null");
    }

    @Test
    void theSameHostIdIsTheSameEndUserInEachConversation() throws Exception {
        Conversation first = started(start("{\"external_user_id\":\"host-user-1\"}"));
        Conversation second = started(start("{\"external_user_id\":\"host-user-1\"}"));

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getEndUserId()).isEqualTo(first.getEndUserId());
    }

    /** A server key names no user. The contract leaves the field optional, because a browser token names its own. */
    @Test
    void aServerKeyNeedsTheHostsUserId() throws Exception {
        assertRefused(start("{}"), NO_USER);
        assertRefused(start("{\"locale\":\"ja-JP\"}"), NO_USER);
        assertRefused(start("{\"external_user_id\":null}"), NO_USER);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 256})
    void theHostsUserIdHasOneTo255Characters(int length) throws Exception {
        assertRefused(start(body(Map.of("external_user_id", "u".repeat(length)))), USER_RULE);
    }

    /** The contract counts characters: 255 symbols outside the basic plane are 510 UTF-16 units, and they pass. */
    @Test
    void theHostsUserIdIsCountedInCharacters() throws Exception {
        String wide = "😀".repeat(255);

        started(start(body(Map.of("external_user_id", wide))));

        assertThat(END_USERS.calls).containsExactly("ten_test/app_test resolves " + wide);
    }

    @Test
    void anIllFormedLocaleIsRefused() throws Exception {
        assertRefused(start("{\"external_user_id\":\"host-user-1\",\"locale\":\"not a tag\"}"), LOCALE_RULE);
        assertRefused(start(body(Map.of("external_user_id", "host-user-1", "locale", "x".repeat(36)))), LOCALE_RULE);
    }

    @Test
    void aBodyThatIsNotJsonIsRefused() throws Exception {
        assertRefused(start("{"), "The body is not valid JSON");
    }

    @Test
    void withoutAKeyNoConversationStarts() throws Exception {
        assertProblem(send(request("/v1/conversations").header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"external_user_id\":\"host-user-1\"}"))), 401, "unauthorized");
        assertNothingMade();
    }

    /** Browser tokens come in M2-12. Until then a session token is just not an API key. */
    @Test
    void aSessionTokenIsNotTakenYet() throws Exception {
        String token = TestApi.SESSION_TOKENS.issue(new SessionTokens.Session(
                "ten_test", "app_test", "eu_1", IdentityLevel.VERIFIED, List.of(), Map.of(), null)).token();

        assertProblem(send(request("/v1/conversations").header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}"))), 401, "unauthorized");
        assertNothingMade();
    }

    /** On-Behalf-Of: the end user and the conversation belong to the lower tenant's application. */
    @Test
    void aPlatformStartsAConversationForItsLowerTenant() throws Exception {
        Conversation conversation = started(start("{\"external_user_id\":\"host-user-1\"}", "On-Behalf-Of", "ten_lower"));

        assertThat(END_USERS.calls).containsExactly("ten_lower/app_lowertest resolves host-user-1");
        assertThat(CONVERSATIONS.calls).containsExactly("ten_lower/app_lowertest starts for " + conversation.getEndUserId() + " in null");
    }

    /** Reading a conversation and the messages come in later points, so their paths still answer 404. */
    @Test
    void theOtherConversationOperationsAreNotHereYet() throws Exception {
        assertProblem(send(request("/v1/conversations/conv_1").header("Authorization", TestApi.AUTHORIZATION)), 404, "not_found");
        assertProblem(send(request("/v1/conversations/conv_1/messages").header("Authorization", TestApi.AUTHORIZATION)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString("{\"text\":\"Hi\"}"))), 404, "not_found");
        assertNothingMade();
    }
}

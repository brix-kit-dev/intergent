package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.http.api.ConversationsApi;
import aie.http.api.model.Conversation;
import aie.http.api.model.Problem;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /v1/conversations and GET /v1/conversations/{id} on the real web stack, with stand-ins for the tenancy and
 * conversation modules.
 */
class ConversationControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInEndUsers END_USERS = new TestApi.StandInEndUsers();
    private static final TestApi.StandInConversations CONVERSATIONS = new TestApi.StandInConversations();
    private static final String NO_USER = "external_user_id is required with a server key";
    private static final String USER_RULE = "external_user_id must have 1 to 255 characters";
    private static final String LOCALE_RULE = "locale must be a BCP 47 language tag of at most 35 characters, such as ja-JP";
    private static final String ID_RULE = "conversation_id must be conv_ followed by 1 to 59 letters or digits";
    /** The longest ID the contract allows: 64 characters in all. */
    private static final String LONGEST_ID = "conv_" + "x".repeat(59);

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

    /** GET /v1/conversations/{id}, with the ID put into the path as it is given, so a test can send escapes. */
    private static HttpResponse<String> read(String id, String... headers) throws Exception {
        HttpRequest.Builder request = request("/v1/conversations/" + id).header("Authorization", TestApi.AUTHORIZATION);
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

    @Test
    void aStartedConversationIsReadBack() throws Exception {
        HttpResponse<String> started = start("{\"external_user_id\":\"host-user-1\",\"locale\":\"ja-JP\"}");
        String id = started(started).getId();

        HttpResponse<String> read = read(id);

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/json"));
        assertThat(read.headers().firstValue("Request-Id")).isPresent();
        assertThat(read.body()).isEqualTo(started.body());
        assertThat(CONVERSATIONS.calls).last().isEqualTo("ten_test/app_test finds " + id);
    }

    @Test
    void aConversationWithoutALocaleIsReadWithoutOne() throws Exception {
        String id = started(start("{\"external_user_id\":\"host-user-1\"}")).getId();

        HttpResponse<String> read = read(id);

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).doesNotContain("locale");
    }

    @Test
    void anUnknownConversationIsNotFound() throws Exception {
        assertProblem(read("conv_nothing"), 404, "not_found");
        assertThat(CONVERSATIONS.calls).containsExactly("ten_test/app_test finds conv_nothing");
    }

    /** The contract's ConversationId allows 64 characters in all, so the longest ID is looked up like any other. */
    @Test
    void theLongestIdIsLookedUp() throws Exception {
        assertThat(LONGEST_ID).hasSize(64);

        assertProblem(read(LONGEST_ID), 404, "not_found");
        assertThat(CONVERSATIONS.calls).containsExactly("ten_test/app_test finds " + LONGEST_ID);
    }

    static Stream<String> malformedIds() {
        return Stream.of("conv_", "conv", "msg_1", "CONV_1", "conv-1", "conv_a-b", "conv_a_b", "conv_a.b",
                "conv_%C3%A9", "conv_%EF%BC%91", "conv_1%0A", "%0Aconv_1", "conv_1%20", "%20conv_1", LONGEST_ID + "x");
    }

    /**
     * Anything but conv_ and then 1 to 59 ASCII letters or digits is refused before any lookup: wrong prefixes, other
     * characters, a full-width digit, a line break or a space at either end, and 65 characters.
     */
    @ParameterizedTest
    @MethodSource("malformedIds")
    void aMalformedIdIsRefused(String id) throws Exception {
        HttpResponse<String> response = read(id);

        assertProblem(response, 400, "invalid_request");
        assertThat(JSON.readValue(response.body(), Problem.class).getDetail()).isEqualTo(ID_RULE);
        assertThat(CONVERSATIONS.calls).as("a malformed ID is not looked up").isEmpty();
    }

    /** Tomcat refuses a NUL in the path by itself, so the request never reaches the engine. It is still a 400. */
    @Test
    void aNulInTheIdIsRefusedBeforeTheEngine() throws Exception {
        assertProblem(read("conv_1%00"), 400, "invalid_request");
        assertThat(CONVERSATIONS.calls).isEmpty();
    }

    /**
     * The controller copies the rule from the contract's ConversationId, which the generated interface carries. If the
     * contract changes the rule, this fails, so the controller's rule and its tests change with it.
     */
    @Test
    void theIdRuleIsTheContracts() throws Exception {
        Parameter id = ConversationsApi.class
                .getMethod("getConversation", String.class, String.class, String.class, String.class).getParameters()[0];

        assertThat(id.getAnnotation(Pattern.class).regexp()).isEqualTo("^conv_[0-9A-Za-z]+$");
        assertThat(id.getAnnotation(Size.class).max()).isEqualTo(64);
    }

    /** One of another application reads exactly like one that does not exist, so a 404 tells nothing more. */
    @Test
    void anotherApplicationsConversationIsNotFound() throws Exception {
        String lowers = started(start("{\"external_user_id\":\"host-user-1\"}", "On-Behalf-Of", "ten_lower")).getId();
        CONVERSATIONS.calls.clear();

        HttpResponse<String> other = read(lowers);
        HttpResponse<String> unknown = read("conv_nothing");

        assertProblem(other, 404, "not_found");
        assertThat(other.body()).isEqualTo(unknown.body());
        assertThat(CONVERSATIONS.calls).containsExactly("ten_test/app_test finds " + lowers, "ten_test/app_test finds conv_nothing");
    }

    /** On-Behalf-Of reads in the lower tenant's application, so the platform's own conversations are not there. */
    @Test
    void aPlatformReadsItsLowerTenantsConversation() throws Exception {
        HttpResponse<String> started = start("{\"external_user_id\":\"host-user-1\"}", "On-Behalf-Of", "ten_lower");
        String lowers = started(started).getId();
        String own = started(start("{\"external_user_id\":\"host-user-1\"}")).getId();
        CONVERSATIONS.calls.clear();

        HttpResponse<String> read = read(lowers, "On-Behalf-Of", "ten_lower");

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).isEqualTo(started.body());
        assertProblem(read(own, "On-Behalf-Of", "ten_lower"), 404, "not_found");
        assertThat(CONVERSATIONS.calls).containsExactly(
                "ten_lower/app_lowertest finds " + lowers, "ten_lower/app_lowertest finds " + own);
    }

    @Test
    void withoutAKeyNothingIsRead() throws Exception {
        assertProblem(send(request("/v1/conversations/conv_nothing")), 401, "unauthorized");
        assertThat(CONVERSATIONS.calls).isEmpty();
    }

    /** Browser tokens come in M2-12. Until then a session token cannot read a conversation either. */
    @Test
    void aSessionTokenCannotReadYet() throws Exception {
        String token = TestApi.SESSION_TOKENS.issue(new SessionTokens.Session(
                "ten_test", "app_test", "eu_1", IdentityLevel.VERIFIED, List.of(), Map.of(), null)).token();

        assertProblem(send(request("/v1/conversations/conv_nothing").header("Authorization", "Bearer " + token)), 401, "unauthorized");
        assertThat(CONVERSATIONS.calls).isEmpty();
    }

    /**
     * Reading messages and cancelling come in later points of M2, so their paths still answer 404, and nothing is looked
     * up. Sending a message works, see MessageControllerTest.
     */
    @Test
    void theOtherMessageOperationsAreNotHereYet() throws Exception {
        assertProblem(send(request("/v1/conversations/conv_1/messages").header("Authorization", TestApi.AUTHORIZATION)), 404, "not_found");
        assertProblem(send(request("/v1/conversations/conv_1/messages/msg_1").header("Authorization", TestApi.AUTHORIZATION)), 404, "not_found");
        assertProblem(send(request("/v1/conversations/conv_1/messages/msg_1/cancel").header("Authorization", TestApi.AUTHORIZATION)
                .POST(BodyPublishers.noBody())), 404, "not_found");
        assertNothingMade();
    }
}

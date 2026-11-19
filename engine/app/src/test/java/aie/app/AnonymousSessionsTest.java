package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.core.tenancy.internal.SignedSessionTokens;
import aie.http.api.model.Problem;
import aie.http.api.model.Session;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * POST /v1/anonymous-sessions on the real web stack, with stand-ins: the site key and the origin open it, an API key
 * does not, and only an allowed origin may read the answer. AnonymousSessionsDatabaseTest runs it on a real database.
 */
class AnonymousSessionsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInEndUsers END_USERS = new TestApi.StandInEndUsers();
    private static final String SHOP = "https://shop.example.com";
    private static final String BODY = "{\"site_key\":\"" + TestApi.SITE_KEY + "\"}";
    /** The end user of a visitor's earlier token, made by hand. */
    private static final String BEFORE = "eu_Before";

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, new TestApi.StandInKeys(), END_USERS, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void forgetCalls() {
        END_USERS.calls.clear();
    }

    /** A browser's POST: JSON, an Origin when one is given, and any further headers as name and value pairs. */
    private static HttpResponse<String> post(String origin, String body, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/anonymous-sessions"))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        if (origin != null) {
            request.header("Origin", origin);
        }
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(JSON.readValue(response.body(), Problem.class).getCode()).isEqualTo(code);
    }

    /** A refusal the page may not read, and that made nothing. */
    private static void assertRefusedUnread(HttpResponse<String> response, int status, String code) {
        assertProblem(response, status, code);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(END_USERS.calls).isEmpty();
    }

    @Test
    void aSiteKeyFromItsOriginGetsAnAnonymousToken() throws Exception {
        HttpResponse<String> response = post(SHOP, "{\"site_key\":\"" + TestApi.SITE_KEY + "\",\"locale\":\"ja-jp\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        Session answer = JSON.readValue(response.body(), Session.class);
        assertThat(answer.getIdentityLevel()).isEqualTo("anonymous");
        assertThat(answer.getScopes()).isEmpty();
        SessionTokens.Verified verified = TestApi.SESSION_TOKENS.verify(answer.getToken()).orElseThrow();
        assertThat(verified.session()).isEqualTo(new SessionTokens.Session(
                "ten_test", "app_test", answer.getEndUserId(), IdentityLevel.ANONYMOUS, List.of(), Map.of(), "ja-JP"));
        assertThat(verified.expiresAt()).isEqualTo(answer.getExpiresAt().toInstant());
        assertThat(END_USERS.calls).containsExactly("ten_test/app_test makes an anonymous end user");
    }

    /** The page reads the answer only because its origin is the one the key lists, and it may read the Request-Id too. */
    @Test
    void onlyTheKeysOriginMayReadTheAnswer() throws Exception {
        HttpResponse<String> response = post("http://localhost:3000", BODY);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3000");
        assertThat(response.headers().firstValue("Access-Control-Expose-Headers")).contains("Request-Id");
        assertThat(response.headers().firstValue("Vary")).contains("Origin");
        assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
    }

    @Test
    void eachSessionIsANewEndUser() throws Exception {
        String first = JSON.readValue(post(SHOP, BODY).body(), Session.class).getEndUserId();
        String second = JSON.readValue(post(SHOP, BODY).body(), Session.class).getEndUserId();

        assertThat(second).isNotEqualTo(first);
        assertThat(END_USERS.calls).hasSize(2);
    }

    /** No API key is needed, and one is not looked at: the site key alone says whose session it is. */
    @Test
    void anApiKeyIsNeitherNeededNorEnough() throws Exception {
        assertThat(post(SHOP, BODY, "Authorization", TestApi.AUTHORIZATION).statusCode()).isEqualTo(201);
        END_USERS.calls.clear();

        assertProblem(post(SHOP, "{}", "Authorization", TestApi.AUTHORIZATION), 400, "invalid_request");
        assertThat(END_USERS.calls).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example", "http://shop.example.com", "https://shop.example.com:8443", "null"})
    void anotherOriginIsForbidden(String origin) throws Exception {
        assertRefusedUnread(post(origin, BODY), 403, "forbidden");
    }

    /** A script that sends no Origin is no browser on a listed site either. */
    @Test
    void noOriginIsForbidden() throws Exception {
        assertRefusedUnread(post(null, BODY), 403, "forbidden");
    }

    @ParameterizedTest
    @ValueSource(strings = {"aie_site_test_dev_9999999999999999999999999999999999999999", "aie_site_test_dev_short",
        "aie_test_dev_0123456789012345678901234567890123456789", ""})
    void aKeyThatIsNotAKnownSiteKeyIsUnauthorized(String siteKey) throws Exception {
        assertRefusedUnread(post(SHOP, "{\"site_key\":\"" + siteKey + "\"}"), 401, "unauthorized");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"site_key\":7}", "{\"site_key\":null}", "[\"site_key\"]", "not json", ""})
    void aBodyWithoutASiteKeyIsABadRequest(String body) throws Exception {
        HttpResponse<String> response = post(SHOP, body);

        assertRefusedUnread(response, 400, "invalid_request");
        assertThat(JSON.readValue(response.body(), Problem.class).getDetail())
                .isEqualTo("The body must be a JSON object with a site_key string");
    }

    /** After the key, the answer is the page's to read, so even a refusal for a bad locale carries the origin. */
    @Test
    void aBadLocaleIsABadRequestThatMakesNoEndUser() throws Exception {
        HttpResponse<String> response = post(SHOP, "{\"site_key\":\"" + TestApi.SITE_KEY + "\",\"locale\":\"ja_JP\"}");

        assertProblem(response, 400, "invalid_request");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(SHOP);
        assertThat(END_USERS.calls).isEmpty();
    }

    /** The version check follows the key check, as it does for server keys. */
    @Test
    void aVersionThisServerDoesNotOfferIsRefusedAfterTheKey() throws Exception {
        HttpResponse<String> response = post(SHOP, BODY, "Engine-Version", "1999-01-01");

        assertProblem(response, 400, "invalid_request");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(SHOP);
        assertThat(END_USERS.calls).isEmpty();
    }

    @Test
    void theBodyMayBeAtMostOneMebibyte() throws Exception {
        String padded = "{\"site_key\":\"" + TestApi.SITE_KEY + "\",\"pad\":\"" + "x".repeat(1024 * 1024) + "\"}";

        assertRefusedUnread(post(SHOP, padded), 413, "invalid_request");
    }

    /** A token for {@link #BEFORE}, as an api with the test's key would have issued it at that moment. */
    private static String token(Instant issuedAt, String tenantId, String applicationId, IdentityLevel level) {
        return new SignedSessionTokens(Clock.fixed(issuedAt, ZoneOffset.UTC), TestApi.SESSION_KEY, List.of())
                .issue(new SessionTokens.Session(tenantId, applicationId, BEFORE, level, List.of(), Map.of(), null))
                .token();
    }

    /** A body that brings the visitor's earlier token, with a locale of its own. */
    private static String renewing(String previousToken) {
        return "{\"site_key\":\"" + TestApi.SITE_KEY + "\",\"locale\":\"en\",\"previous_token\":\"" + previousToken + "\"}";
    }

    private static String detail(HttpResponse<String> response) {
        return JSON.readValue(response.body(), Problem.class).getDetail();
    }

    /** A visitor who comes back with their token keeps their end user, and with it their conversations (point 43). */
    @Test
    void aVisitorWhoBringsTheirTokenKeepsTheirEndUser() throws Exception {
        Session first = JSON.readValue(post(SHOP, BODY).body(), Session.class);
        END_USERS.calls.clear();

        HttpResponse<String> response = post(SHOP, renewing(first.getToken()));

        assertThat(response.statusCode()).isEqualTo(201);
        Session renewed = JSON.readValue(response.body(), Session.class);
        assertThat(renewed.getEndUserId()).isEqualTo(first.getEndUserId());
        assertThat(renewed.getIdentityLevel()).isEqualTo("anonymous");
        assertThat(TestApi.SESSION_TOKENS.verify(renewed.getToken()).orElseThrow().session()).isEqualTo(new SessionTokens.Session(
                "ten_test", "app_test", first.getEndUserId(), IdentityLevel.ANONYMOUS, List.of(), Map.of(), "en"));
        assertThat(END_USERS.calls).as("no end user is made").isEmpty();
    }

    /** A token that expired days ago still names its visitor, and the new one lives an hour from now. */
    @Test
    void aTokenThatExpiredDaysAgoKeepsItsEndUser() throws Exception {
        Instant issued = Instant.now().minus(SessionTokens.LIFETIME).minus(SessionTokens.RENEWAL_WINDOW).plus(Duration.ofMinutes(5));

        HttpResponse<String> response = post(SHOP, renewing(token(issued, "ten_test", "app_test", IdentityLevel.ANONYMOUS)));

        assertThat(response.statusCode()).isEqualTo(201);
        Session renewed = JSON.readValue(response.body(), Session.class);
        assertThat(renewed.getEndUserId()).isEqualTo(BEFORE);
        assertThat(Duration.between(Instant.now(), renewed.getExpiresAt().toInstant()))
                .isBetween(SessionTokens.LIFETIME.minusMinutes(1), SessionTokens.LIFETIME);
        assertThat(END_USERS.calls).isEmpty();
    }

    static Stream<Arguments> tokensThatCannotBeRenewed() {
        Instant now = Instant.now();
        Instant tooLongAgo = now.minus(SessionTokens.LIFETIME).minus(SessionTokens.RENEWAL_WINDOW).minus(Duration.ofMinutes(5));
        byte[] otherKey = new byte[32];
        Arrays.fill(otherKey, (byte) 9);
        String otherKeys = new SignedSessionTokens(Clock.systemUTC(), otherKey, List.of()).issue(new SessionTokens.Session(
                "ten_test", "app_test", BEFORE, IdentityLevel.ANONYMOUS, List.of(), Map.of(), null)).token();
        return Stream.of(
                Arguments.of("expired seven days and five minutes ago", token(tooLongAgo, "ten_test", "app_test", IdentityLevel.ANONYMOUS)),
                Arguments.of("a host's verified end user", token(now, "ten_test", "app_test", IdentityLevel.VERIFIED)),
                Arguments.of("a host's unverified end user", token(now, "ten_test", "app_test", IdentityLevel.UNVERIFIED)),
                Arguments.of("another application's visitor", token(now, "ten_test", "app_other", IdentityLevel.ANONYMOUS)),
                Arguments.of("another tenant's visitor", token(now, "ten_other", "app_test", IdentityLevel.ANONYMOUS)),
                Arguments.of("signed with another key", otherKeys),
                Arguments.of("not a token", "not a token"),
                Arguments.of("empty", ""));
    }

    /** A token that cannot be renewed is no error: the visitor is a new one, as without a token. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("tokensThatCannotBeRenewed")
    void aTokenThatCannotBeRenewedMakesANewVisitor(String what, String previousToken) throws Exception {
        HttpResponse<String> response = post(SHOP, renewing(previousToken));

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JSON.readValue(response.body(), Session.class).getEndUserId()).isNotEqualTo(BEFORE);
        assertThat(END_USERS.calls).containsExactly("ten_test/app_test makes an anonymous end user");
    }

    /** Like any string of a body, a token with a NUL is refused before anything is made (point 37). */
    @Test
    void aPreviousTokenWithANulIsABadRequestThatMakesNoEndUser() throws Exception {
        HttpResponse<String> response = post(SHOP, renewing("abc\\u0000def"));

        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo("previous_token cannot hold a NUL character");
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(SHOP);
        assertThat(END_USERS.calls).isEmpty();
    }

    /** The contract counts characters, so 4096 symbols outside the basic plane pass too. One more is refused. */
    @Test
    void aPreviousTokenMayHaveAtMost4096Characters() throws Exception {
        assertThat(post(SHOP, renewing("a".repeat(4096))).statusCode()).isEqualTo(201);
        assertThat(post(SHOP, renewing("😀".repeat(4096))).statusCode()).isEqualTo(201);
        END_USERS.calls.clear();

        HttpResponse<String> response = post(SHOP, renewing("a".repeat(4097)));

        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo("previous_token must have at most 4096 characters");
        assertThat(END_USERS.calls).isEmpty();
    }
}

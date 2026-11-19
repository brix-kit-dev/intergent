package aie.app;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.http.api.IdentityApi;
import aie.http.api.model.CreatedApiKey;
import aie.http.api.model.Problem;
import aie.http.api.model.Session;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** The identity operations on the real web stack, with stand-ins for the tenancy module. */
class IdentityControllerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TestApi.StandInKeys KEYS = new TestApi.StandInKeys();
    private static final TestApi.StandInEndUsers END_USERS = new TestApi.StandInEndUsers();
    private static final String CALLER = "key_test of ten_test/app_test";
    private static final String SCOPE_RULE = "each scope must have 1 to 64 letters, digits or _ . : -";
    private static final String KEY_RULE = "each context key must have 1 to 40 letters, digits or _ . : -";
    private static final String VALUE_RULE = "each context value must be a string of at most 200 characters";
    private static final String LOCALE_RULE = "locale must be a BCP 47 language tag of at most 35 characters, such as ja-JP";
    private static final String KEY_ID_RULE = "key_id must be key_ followed by 1 to 60 letters or digits";
    private static final String LONGEST_KEY_ID = "key_" + "x".repeat(60);

    private static ConfigurableApplicationContext context;
    private static int port;

    @BeforeAll
    static void start() {
        context = TestApi.start(TestApi.LOOKUP, KEYS, END_USERS, "--server.port=0");
        port = TestApi.port(context);
    }

    @AfterAll
    static void stop() {
        context.close();
    }

    @BeforeEach
    void forgetCalls() {
        KEYS.calls.clear();
        END_USERS.calls.clear();
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> revoke(String keyId) throws Exception {
        return send(request("/v1/api-keys/" + keyId).header("Authorization", TestApi.AUTHORIZATION).DELETE());
    }

    private static HttpResponse<String> create(String body) throws Exception {
        return send(request("/v1/api-keys").header("Authorization", TestApi.AUTHORIZATION)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body)));
    }

    private static HttpResponse<String> createSession(String body, String... headers) throws Exception {
        HttpRequest.Builder request = request("/v1/sessions").header("Authorization", TestApi.AUTHORIZATION)
                .header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return send(request);
    }

    /** A body with the host ID u1 and these fields, written by Jackson so that every string is escaped properly. */
    private static String sessionBody(Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("external_user_id", "u1");
        body.putAll(fields);
        return JSON.writeValueAsString(body);
    }

    /** What the token in a 201 says, checked with the key the stand-in api signs with. */
    private static SessionTokens.Session carried(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(201);
        return TestApi.SESSION_TOKENS.verify(JSON.readValue(response.body(), Session.class).getToken()).orElseThrow().session();
    }

    private static void assertRefused(HttpResponse<String> response, String detail) {
        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo(detail);
        assertThat(END_USERS.calls).as("a refused request makes no end user").isEmpty();
    }

    private static String detail(HttpResponse<String> response) {
        return JSON.readValue(response.body(), Problem.class).getDetail();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(JSON.readValue(response.body(), Problem.class).getCode()).isEqualTo(code);
    }

    @Test
    void revokingAKeyOfTheApplicationAnswersNoContent() throws Exception {
        KEYS.allow("key_other");

        HttpResponse<String> response = revoke("key_other");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        assertThat(KEYS.calls).containsExactly(CALLER + " revokes key_other");
    }

    @Test
    void aKeyTheApplicationDoesNotHaveIsNotFound() throws Exception {
        assertProblem(revoke("key_missing"), 404, "not_found");
        assertThat(KEYS.calls).containsExactly(CALLER + " revokes key_missing");
    }

    @Test
    void aKeyRevokedOnceIsNotFoundTheSecondTime() throws Exception {
        KEYS.allow("key_twice");

        assertThat(revoke("key_twice").statusCode()).isEqualTo(204);
        assertProblem(revoke("key_twice"), 404, "not_found");
    }

    @Test
    void revokingNeedsAKey() throws Exception {
        assertProblem(send(request("/v1/api-keys/key_other").DELETE()), 401, "unauthorized");
        assertThat(KEYS.calls).isEmpty();
    }

    /** On-Behalf-Of: the platform's key revokes a key of the lower tenant's application. */
    @Test
    void aPlatformRevokesAKeyOfItsLowerTenant() throws Exception {
        KEYS.allow("key_lower");

        HttpResponse<String> response = send(request("/v1/api-keys/key_lower")
                .header("Authorization", TestApi.AUTHORIZATION).header("On-Behalf-Of", "ten_lower").DELETE());

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(KEYS.calls).containsExactly("key_test of ten_lower/app_lowertest revokes key_lower");
    }

    @Test
    void actingForATenantThatIsNotALowerTenantIsForbidden() throws Exception {
        HttpResponse<String> response = send(request("/v1/api-keys/key_other")
                .header("Authorization", TestApi.AUTHORIZATION).header("On-Behalf-Of", "ten_other").DELETE());

        assertProblem(response, 403, "forbidden");
        assertThat(KEYS.calls).isEmpty();
    }

    @Test
    void aKeyCanOnlyBeDeleted() throws Exception {
        assertProblem(send(request("/v1/api-keys/key_other").header("Authorization", TestApi.AUTHORIZATION)), 405, "invalid_request");
    }

    static Stream<String> malformedKeyIds() {
        return Stream.of("key_", "key", "conv_1", "KEY_1", "key-1", "key_a-b", "key_a_b", "key_a.b",
                "key_%C3%A9", "key_%EF%BC%91", "key_1%0A", "%0Akey_1", "key_1%20", "%20key_1", LONGEST_KEY_ID + "x");
    }

    /**
     * Point 36: anything but key_ and then 1 to 60 ASCII letters or digits is refused before any lookup, as a malformed
     * conversation ID is: wrong prefixes, other characters, a full-width digit, a line break or a space at either end,
     * and 65 characters.
     */
    @ParameterizedTest
    @MethodSource("malformedKeyIds")
    void aMalformedKeyIdIsRefused(String keyId) throws Exception {
        HttpResponse<String> response = revoke(keyId);

        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo(KEY_ID_RULE);
        assertThat(KEYS.calls).as("a malformed key ID is not looked up").isEmpty();
    }

    /** The contract's KeyId allows 64 characters in all, so the longest ID is looked up like any other. */
    @Test
    void theLongestKeyIdIsLookedUp() throws Exception {
        assertThat(LONGEST_KEY_ID).hasSize(64);

        assertProblem(revoke(LONGEST_KEY_ID), 404, "not_found");
        assertThat(KEYS.calls).containsExactly(CALLER + " revokes " + LONGEST_KEY_ID);
    }

    /** Tomcat refuses a NUL in the path by itself, so the request never reaches the engine. It is still a 400. */
    @Test
    void aNulInTheKeyIdIsRefusedBeforeTheEngine() throws Exception {
        assertProblem(revoke("key_1%00"), 400, "invalid_request");
        assertThat(KEYS.calls).isEmpty();
    }

    /**
     * The caller comes first, as on every operation: without a key, with a session token, or for a tenant that is not a
     * lower one, a malformed key ID gets the answer a well-formed one would.
     */
    @Test
    void theCallerIsCheckedBeforeTheKeyId() throws Exception {
        assertProblem(send(request("/v1/api-keys/key_a-b").DELETE()), 401, "unauthorized");
        assertProblem(send(request("/v1/api-keys/key_a-b")
                .header("Authorization", "Bearer " + TestApi.sessionToken("eu_1")).DELETE()), 403, "forbidden");
        assertProblem(send(request("/v1/api-keys/key_a-b")
                .header("Authorization", TestApi.AUTHORIZATION).header("On-Behalf-Of", "ten_other").DELETE()), 403, "forbidden");
        assertThat(KEYS.calls).isEmpty();
    }

    /**
     * The controller copies the rule from the contract's KeyId, which the generated interface carries. If the contract
     * changes the rule, this fails, so the controller's rule and its tests change with it.
     */
    @Test
    void theKeyIdRuleIsTheContracts() throws Exception {
        Parameter id = IdentityApi.class
                .getMethod("deleteApiKey", String.class, String.class, String.class, String.class).getParameters()[0];

        assertThat(id.getAnnotation(Pattern.class).regexp()).isEqualTo("^key_[0-9A-Za-z]+$");
        assertThat(id.getAnnotation(Size.class).max()).isEqualTo(64);
    }

    @Test
    void creatingAKeyAnswersCreatedWithTheKeyOnce() throws Exception {
        HttpResponse<String> response = create("{\"name\":\"rotation\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        CreatedApiKey created = JSON.readValue(response.body(), CreatedApiKey.class);
        assertThat(created.getId()).isEqualTo("key_created1");
        assertThat(created.getKey()).isEqualTo("aie_test_dev_" + "5".repeat(40));
        assertThat(created.getName()).isEqualTo("rotation");
        assertThat(response.body()).contains("\"created_at\":\"2026-11-08T00:00:00Z\"").doesNotContain("expires_at");
        assertThat(KEYS.calls).containsExactly(CALLER + " creates rotation");
    }

    @Test
    void aKeyNeedsNoName() throws Exception {
        HttpResponse<String> response = create("{}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).doesNotContain("\"name\"");
        assertThat(KEYS.calls).containsExactly(CALLER + " creates null");
    }

    @Test
    void anExpiryInTheFutureIsKept() throws Exception {
        HttpResponse<String> response = create("{\"expires_at\":\"2030-01-01T09:00:00+09:00\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"expires_at\":\"2030-01-01T00:00:00Z\"");
        assertThat(KEYS.calls).containsExactly(CALLER + " creates null until 2030-01-01T00:00:00Z");
    }

    /** A key that would be dead on arrival is a mistake of the caller, so it gets no key at all. */
    @ParameterizedTest
    @ValueSource(strings = {"2020-01-01T00:00:00Z", "2026-11-08T00:00:00Z"})
    void anExpiryThatHasPassedIsRefused(String expiry) throws Exception {
        HttpResponse<String> response = create("{\"expires_at\":\"" + expiry + "\"}");

        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo("expires_at must be in the future");
        assertThat(KEYS.calls).isEmpty();
    }

    /**
     * Point 46: an expiry is taken in the contract's form, RFC 3339, and in all of it: T and Z in either case, a fraction
     * of a second, and any offset written with a colon, -00:00 too.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            2030-01-01T00:00:00Z           | 2030-01-01T00:00:00Z
            2030-01-01T09:00:00+09:00      | 2030-01-01T00:00:00Z
            2029-12-31T19:00:00-05:00      | 2030-01-01T00:00:00Z
            2030-01-01t00:00:00z           | 2030-01-01T00:00:00Z
            2030-01-01T00:00:00-00:00      | 2030-01-01T00:00:00Z
            2030-01-01T00:00:00.5Z         | 2030-01-01T00:00:00.500Z
            2030-01-01T00:00:00.123456789Z | 2030-01-01T00:00:00.123456789Z
            """)
    void anExpiryInTheContractsFormIsKept(String expiry, String kept) throws Exception {
        HttpResponse<String> response = create("{\"expires_at\":\"" + expiry + "\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(KEYS.calls).containsExactly(CALLER + " creates null until " + kept);
    }

    /**
     * Point 46: any other form is refused before a key is made, with the 400 of any value that is no date. Jackson's own
     * reader trimmed the text, so a NUL or a space at either end passed; it read an empty or blank string as no expiry,
     * which made a key that never expires; and it took a number of seconds and an offset without a colon.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "\"\\u00002030-01-01T00:00:00Z\"", "\"2030-01-01T00:00:00Z\\u0000\"", "\"\\u0000\"",
        "\" 2030-01-01T00:00:00Z\"", "\"2030-01-01T00:00:00Z \"", "\"\\t2030-01-01T00:00:00Z\"", "\"2030-01-01T00:00:00Z\\n\"",
        "\"\"", "\"   \"", "1893456000", "1893456000.5",
        "\"2030-01-01T09:00:00+0900\"", "\"2030-01-01T09:00:00+09\"", "\"2030-01-01T09:00:00+09:00:00\"",
        "\"2030-01-01T00:00Z\"", "\"+12030-01-01T00:00:00Z\"", "\"2030-02-30T00:00:00Z\"", "\"2030-01-01 00:00:00Z\"",
        "true", "[]", "{}"})
    void anExpiryInAnyOtherFormIsRefused(String value) throws Exception {
        HttpResponse<String> response = create("{\"expires_at\":" + value + "}");

        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo("expires_at must be a date and time such as 2026-01-01T00:00:00Z");
        assertThat(KEYS.calls).isEmpty();
    }

    /** A JSON null is no expiry, as leaving the field out is; point 46 keeps it so. */
    @Test
    void aNullExpiryIsNoExpiry() throws Exception {
        HttpResponse<String> response = create("{\"expires_at\":null}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).doesNotContain("expires_at");
        assertThat(KEYS.calls).containsExactly(CALLER + " creates null");
    }

    /** The contract counts characters, so a name of 100 symbols outside the basic plane passes too. */
    @Test
    void aNameMayHaveOneHundredCharactersAndNoMore() throws Exception {
        assertThat(create("{\"name\":\"" + "n".repeat(100) + "\"}").statusCode()).isEqualTo(201);
        assertThat(create("{\"name\":\"" + "\uD83D\uDD11".repeat(100) + "\"}").statusCode()).isEqualTo(201);

        HttpResponse<String> tooLong = create("{\"name\":\"" + "n".repeat(101) + "\"}");

        assertProblem(tooLong, 400, "invalid_request");
        assertThat(detail(tooLong)).isEqualTo("name may be at most 100 characters");
        assertThat(KEYS.calls).hasSize(2);
    }

    /** Point 37: the database cannot store a NUL, so a name with one is refused before any key is made. */
    @ParameterizedTest
    @ValueSource(strings = {"\0", "rotation\0", "\0rotation", "rot\0ation"})
    void aNameHoldsNoNul(String name) throws Exception {
        HttpResponse<String> response = create(JSON.writeValueAsString(Map.of("name", name)));

        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo("name cannot hold a NUL character");
        assertThat(KEYS.calls).isEmpty();
    }

    @Test
    void creatingNeedsAKey() throws Exception {
        assertProblem(send(request("/v1/api-keys").header("Content-Type", "application/json").POST(BodyPublishers.ofString("{}"))),
                401, "unauthorized");
        assertThat(KEYS.calls).isEmpty();
    }

    @Test
    void aSessionAnswersCreatedWithATokenForTheEndUser() throws Exception {
        Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        HttpResponse<String> response = createSession(
                "{\"external_user_id\":\"host-user-1\",\"scopes\":[\"orders:read\"],\"context\":{\"order_id\":\"A-1001\"},\"locale\":\"ja-jp\"}");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Request-Id")).isPresent();
        Session answer = JSON.readValue(response.body(), Session.class);
        assertThat(answer.getIdentityLevel()).isEqualTo("verified");
        assertThat(answer.getScopes()).containsExactly("orders:read");
        assertThat(answer.getExpiresAt().toInstant()).isBetween(before.plus(SessionTokens.LIFETIME), Instant.now().plus(SessionTokens.LIFETIME));
        SessionTokens.Verified verified = TestApi.SESSION_TOKENS.verify(answer.getToken()).orElseThrow();
        assertThat(verified.session()).isEqualTo(new SessionTokens.Session("ten_test", "app_test", answer.getEndUserId(),
                IdentityLevel.VERIFIED, List.of("orders:read"), Map.of("order_id", "A-1001"), "ja-JP"));
        assertThat(verified.expiresAt()).isEqualTo(answer.getExpiresAt().toInstant());
        assertThat(END_USERS.calls).containsExactly("ten_test/app_test resolves host-user-1");
    }

    @Test
    void aSessionNeedsOnlyTheHostsIdForTheEndUser() throws Exception {
        HttpResponse<String> response = createSession("{\"external_user_id\":\"host-user-2\"}");

        assertThat(carried(response)).isEqualTo(new SessionTokens.Session("ten_test", "app_test",
                JSON.readValue(response.body(), Session.class).getEndUserId(), IdentityLevel.VERIFIED, List.of(), Map.of(), null));
        assertThat(JSON.readValue(response.body(), Session.class).getScopes()).isEmpty();
    }

    @Test
    void theSameHostIdGetsTheSameEndUser() throws Exception {
        String first = JSON.readValue(createSession("{\"external_user_id\":\"host-user-3\"}").body(), Session.class).getEndUserId();
        String again = JSON.readValue(createSession("{\"external_user_id\":\"host-user-3\"}").body(), Session.class).getEndUserId();
        String other = JSON.readValue(createSession("{\"external_user_id\":\"host-user-4\"}").body(), Session.class).getEndUserId();

        assertThat(again).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void theHostMayVouchLess() throws Exception {
        assertThat(carried(createSession("{\"external_user_id\":\"u1\",\"identity_level\":\"unverified\"}")).identityLevel())
                .isEqualTo(IdentityLevel.UNVERIFIED);
    }

    /** Anonymous tokens come from site keys, so a server key cannot ask for one. Jackson refuses the value. */
    @ParameterizedTest
    @ValueSource(strings = {"anonymous", "admin", "VERIFIED"})
    void anotherIdentityLevelIsRefused(String level) throws Exception {
        HttpResponse<String> response = createSession("{\"external_user_id\":\"u1\",\"identity_level\":\"" + level + "\"}");

        assertRefused(response, "identity_level must be one of: unverified, verified");
        assertThat(response.body()).doesNotContain(level, "aie.", "Enum");
    }

    /** The model cannot hold these, so Jackson refuses them before the checks; the detail still names the field. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"external_user_id":{}}                       | external_user_id must be a string
            {"external_user_id":"u1","scopes":"read"}     | scopes must be an array
            {"external_user_id":"u1","scopes":["a",[]]}   | scopes[1] must be a string
            {"external_user_id":"u1","context":"x"}       | context must be an object
            {"external_user_id":"u1","context":{"k":[]}}  | each value in context must be a string
            """)
    void aSessionFieldOfTheWrongTypeIsNamed(String body, String detail) throws Exception {
        assertRefused(createSession(body), detail);
    }

    @Test
    void anExpiryThatIsNoDateIsNamed() throws Exception {
        HttpResponse<String> response = create("{\"expires_at\":\"tomorrow\"}");

        assertProblem(response, 400, "invalid_request");
        assertThat(detail(response)).isEqualTo("expires_at must be a date and time such as 2026-01-01T00:00:00Z");
        assertThat(response.body()).doesNotContain("tomorrow", "OffsetDateTime");
        assertThat(KEYS.calls).isEmpty();
    }

    /** The contract counts characters, so a host ID of 255 symbols outside the basic plane passes too. */
    @Test
    void theHostsIdHasOneTo255Characters() throws Exception {
        String rule = "external_user_id must have 1 to 255 characters";
        assertRefused(createSession("{}"), rule);
        assertRefused(createSession("{\"external_user_id\":\"\"}"), rule);
        assertRefused(createSession("{\"external_user_id\":null}"), rule);
        assertRefused(createSession("{\"external_user_id\":\"" + "u".repeat(256) + "\"}"), rule);

        assertThat(createSession("{\"external_user_id\":\"" + "u".repeat(255) + "\"}").statusCode()).isEqualTo(201);
        assertThat(createSession("{\"external_user_id\":\"" + "😀".repeat(255) + "\"}").statusCode()).isEqualTo(201);
    }

    /** Point 37: the database cannot store a NUL, so a host's ID with one is refused before any end user is made. */
    @ParameterizedTest
    @ValueSource(strings = {"\0", "u1\0", "\0u1", "host\0user"})
    void theHostsIdHoldsNoNul(String id) throws Exception {
        assertRefused(createSession(sessionBody(Map.of("external_user_id", id))), "external_user_id cannot hold a NUL character");
    }

    @Test
    void aSessionHasAtMostTwentyScopes() throws Exception {
        assertRefused(createSession(sessionBody(Map.of("scopes", names("s", 21)))), "scopes may have at most 20 entries");

        assertThat(carried(createSession(sessionBody(Map.of("scopes", names("s", 20))))).scopes()).hasSize(20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "orders read", "orders/read", "orders,read", "注文", "orders\n"})
    void aScopeIsAName(String scope) throws Exception {
        assertRefused(createSession(sessionBody(Map.of("scopes", List.of("faq", scope)))), SCOPE_RULE);
    }

    @Test
    void aScopeHasAtMostSixtyFourCharacters() throws Exception {
        assertRefused(createSession(sessionBody(Map.of("scopes", List.of("s".repeat(65))))), SCOPE_RULE);

        List<String> scopes = List.of("s".repeat(64), "Orders.Read:2_x-y");
        assertThat(carried(createSession(sessionBody(Map.of("scopes", scopes)))).scopes()).isEqualTo(scopes);
    }

    @Test
    void aScopeIsNeverNull() throws Exception {
        assertRefused(createSession("{\"external_user_id\":\"u1\",\"scopes\":[\"faq\",null]}"), SCOPE_RULE);
    }

    @Test
    void aSessionHasAtMostTenContextEntries() throws Exception {
        assertRefused(createSession(sessionBody(Map.of("context", entries(11, 1)))), "context may have at most 10 entries");

        assertThat(carried(createSession(sessionBody(Map.of("context", entries(10, 1))))).context()).hasSize(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "order id", "order/id", "注文", "order\n"})
    void aContextKeyIsAName(String key) throws Exception {
        assertRefused(createSession(sessionBody(Map.of("context", Map.of(key, "A-1001")))), KEY_RULE);
    }

    @Test
    void aContextKeyHasAtMostFortyCharacters() throws Exception {
        assertRefused(createSession(sessionBody(Map.of("context", Map.of("k".repeat(41), "v")))), KEY_RULE);

        assertThat(carried(createSession(sessionBody(Map.of("context", Map.of("k".repeat(40), "v"))))).context())
                .containsOnlyKeys("k".repeat(40));
    }

    /** Characters, not UTF-16 units: 200 symbols outside the basic plane pass, and 201 letters do not. */
    @Test
    void aContextValueHasAtMost200Characters() throws Exception {
        assertRefused(createSession(sessionBody(Map.of("context", Map.of("note", "v".repeat(201))))), VALUE_RULE);
        assertRefused(createSession("{\"external_user_id\":\"u1\",\"context\":{\"note\":null}}"), VALUE_RULE);

        assertThat(carried(createSession(sessionBody(Map.of("context", Map.of("note", "😀".repeat(200)))))).context())
                .containsEntry("note", "😀".repeat(200));
    }

    /**
     * Point 37: the context goes on to the host's actions (blueprint 6.6, M5), and no value there has a use for a NUL, so
     * one with a NUL anywhere is refused, even next to good ones.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\0", "A-1001\0", "\0A-1001", "A\0B"})
    void aContextValueHoldsNoNul(String value) throws Exception {
        String rule = "each context value cannot hold a NUL character";
        assertRefused(createSession(sessionBody(Map.of("context", Map.of("order_id", value)))), rule);
        assertRefused(createSession(sessionBody(Map.of("context", Map.of("shop", "tokyo", "order_id", value)))), rule);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "ja_JP", " ja-JP", "日本語", "en-US-x-aaaaaaaa-bbbbbbbb-cccccccc-d"})
    void aLocaleIsAWellFormedTag(String locale) throws Exception {
        assertRefused(createSession(sessionBody(Map.of("locale", locale))), LOCALE_RULE);
    }

    /** The limit is the JSON as the token writes it, so 2048 bytes pass and one more does not. */
    @Test
    void scopesAndContextTakeAtMost2048Bytes() throws Exception {
        Map<String, String> context = entries(9, 200);
        context.put("k0", "v".repeat(167));
        assertThat(context).hasSize(10);
        assertThat(JSON.writeValueAsString(context).getBytes(UTF_8)).hasSize(2048);
        Map<String, String> oneMore = new LinkedHashMap<>(context);
        oneMore.put("k0", "v".repeat(168));

        assertRefused(createSession(sessionBody(Map.of("context", oneMore))),
                "scopes and context may take at most 2048 bytes together as JSON, and these take 2049");
        assertThat(carried(createSession(sessionBody(Map.of("context", context)))).context()).isEqualTo(context);
    }

    /** A control character takes six bytes as JSON, so ten values of them are refused though each has 200 characters. */
    @Test
    void escapesCountAsTheBytesTheyTake() throws Exception {
        Map<String, String> context = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            context.put("k" + i, "\u0001".repeat(200));
        }

        assertRefused(createSession(sessionBody(Map.of("context", context))),
                "scopes and context may take at most 2048 bytes together as JSON, and these take "
                        + JSON.writeValueAsString(context).getBytes(UTF_8).length);
    }

    /** On-Behalf-Of: the end user and the token belong to the lower tenant's application. */
    @Test
    void aPlatformMakesASessionForItsLowerTenant() throws Exception {
        HttpResponse<String> response = createSession("{\"external_user_id\":\"host-user-1\"}", "On-Behalf-Of", "ten_lower");

        SessionTokens.Session session = carried(response);
        assertThat(session.tenantId()).isEqualTo("ten_lower");
        assertThat(session.applicationId()).isEqualTo("app_lowertest");
        assertThat(END_USERS.calls).containsExactly("ten_lower/app_lowertest resolves host-user-1");
    }

    @Test
    void aSessionNeedsAKey() throws Exception {
        assertProblem(send(request("/v1/sessions").header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString("{\"external_user_id\":\"u1\"}"))), 401, "unauthorized");
        assertThat(END_USERS.calls).isEmpty();
    }

    /** Names made of a prefix and a number, such as s1, s2 and so on. */
    private static List<String> names(String prefix, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> prefix + i).toList();
    }

    /** Entries k1, k2 and so on, each with a value of the given length. */
    private static Map<String, String> entries(int count, int valueLength) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) {
            entries.put("k" + i, "v".repeat(valueLength));
        }
        return entries;
    }
}

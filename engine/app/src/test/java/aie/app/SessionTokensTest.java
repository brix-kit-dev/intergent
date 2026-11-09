package aie.app;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.Ids;
import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.core.tenancy.api.SessionTokens.Issued;
import aie.core.tenancy.api.SessionTokens.Session;
import aie.core.tenancy.api.SessionTokens.Verified;
import aie.core.tenancy.internal.SignedSessionTokens;
import aie.http.api.model.CreateSessionRequest;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.SignedJWT;
import jakarta.validation.constraints.Size;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Session tokens: what they carry, how long they live, and every way a token that the engine did not issue, or that
 * has run out, must fail. Bad tokens are built by hand with javax.crypto, so the checks do not trust the library that
 * made the good ones.
 */
class SessionTokensTest {

    private static final byte[] KEY = key(1, 32);
    private static final byte[] NEXT_KEY = key(2, 32);
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final long IAT = NOW.getEpochSecond();
    private static final long EXP = IAT + 3600;
    private static final Session SESSION = new Session(
            "ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of("orders.read"), Map.of("order", "A-1001"), "ja-JP");
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();
    /** A well-formed tag of exactly {@link SessionTokens#MAX_LOCALE} characters, and one a character longer. */
    private static final String LONGEST_LOCALE = "en-US-x-aaaaaaa-bbbbbbbb-cccccccc-d";
    private static final String TOO_LONG_LOCALE = "en-US-x-aaaaaaaa-bbbbbbbb-cccccccc-d";

    private static byte[] key(int seed, int length) {
        byte[] key = new byte[length];
        Arrays.fill(key, (byte) seed);
        return key;
    }

    private static SessionTokens tokens(Instant now, byte[] signingKey, byte[]... previousKeys) {
        return new SignedSessionTokens(Clock.fixed(now, ZoneOffset.UTC), signingKey, List.of(previousKeys));
    }

    /** The key ID, worked out here without the code under test: the first 8 bytes of SHA-256, in hex. */
    private static String kid(byte[] key) throws Exception {
        return HexFormat.of().formatHex(Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(key), 8));
    }

    /** A token built by hand. {@code mac} is a javax.crypto name such as HmacSHA256, or null for no signature. */
    private static String handMade(String header, String claims, byte[] key, String mac) throws Exception {
        String input = URL.encodeToString(header.getBytes(UTF_8)) + "." + URL.encodeToString(claims.getBytes(UTF_8));
        if (mac == null) {
            return input + ".";
        }
        Mac hmac = Mac.getInstance(mac);
        hmac.init(new SecretKeySpec(key, mac));
        return input + "." + URL.encodeToString(hmac.doFinal(input.getBytes(US_ASCII)));
    }

    private static String header(String alg, byte[] key) throws Exception {
        return "{\"alg\":\"" + alg + "\",\"kid\":\"" + kid(key) + "\",\"typ\":\"JWT\"}";
    }

    private static String claims(String more) {
        return "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"iat\":" + IAT + ",\"exp\":" + EXP + more + "}";
    }

    @Test
    void aTokenCarriesItsSessionForOneHour() {
        Issued issued = tokens(NOW, KEY).issue(SESSION);

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(tokens(NOW, KEY).verify(issued.token())).contains(new Verified(SESSION, issued.expiresAt()));
    }

    @Test
    void aSessionWithoutScopesContextOrLocaleComesBackEmpty() {
        Session bare = new Session("ten_A", "app_A", "eu_A", IdentityLevel.UNVERIFIED, List.of(), Map.of(), null);

        assertThat(tokens(NOW, KEY).verify(tokens(NOW, KEY).issue(bare).token()))
                .hasValueSatisfying(verified -> assertThat(verified.session()).isEqualTo(bare));
    }

    /** The locale travels as the claim {@code loc}, and only when the host gave one. */
    @Test
    void theLocaleTravelsAsLocOnlyWhenTheHostGaveOne() throws Exception {
        Session without = new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of(), Map.of(), null);

        assertThat(SignedJWT.parse(tokens(NOW, KEY).issue(SESSION).token()).getJWTClaimsSet().getClaims())
                .containsEntry("loc", "ja-JP");
        assertThat(SignedJWT.parse(tokens(NOW, KEY).issue(without).token()).getJWTClaimsSet().getClaims())
                .doesNotContainKey("loc");
    }

    @Test
    void theHeaderNamesHs256AndTheKey() throws Exception {
        JWSHeader header = SignedJWT.parse(tokens(NOW, KEY).issue(SESSION).token()).getHeader();

        assertThat(header.toJSONObject()).isEqualTo(Map.of("alg", "HS256", "kid", kid(KEY), "typ", "JWT"));
    }

    /** The second is cut off, because a token counts time in whole seconds. */
    @Test
    void theLifetimeStartsAtTheWholeSecond() {
        assertThat(tokens(NOW.plusMillis(999), KEY).issue(SESSION).expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void aTokenWorksUntilItsLastSecondAndNotAfter() {
        String token = tokens(NOW, KEY).issue(SESSION).token();

        assertThat(tokens(NOW.plus(Duration.ofHours(1)).minusMillis(1), KEY).verify(token)).isPresent();
        assertThat(tokens(NOW.plus(Duration.ofHours(1)), KEY).verify(token)).isEmpty();
    }

    /** Proves the hand-made tokens below are right, so their failures come from what each one changes. */
    @Test
    void aHandMadeTokenWithTheRightPartsWorks() throws Exception {
        String token = handMade(header("HS256", KEY), claims(""), KEY, "HmacSHA256");

        assertThat(tokens(NOW, KEY).verify(token)).contains(new Verified(
                new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of(), Map.of(), null), Instant.ofEpochSecond(EXP)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ja-JP", "en", "zh-Hans-CN", "en-US-x-twain", LONGEST_LOCALE})
    void aHandMadeTokenWithALocaleInStandardFormWorks(String locale) throws Exception {
        String token = handMade(header("HS256", KEY), claims(",\"loc\":\"" + locale + "\""), KEY, "HmacSHA256");

        assertThat(tokens(NOW, KEY).verify(token)).hasValueSatisfying(verified -> assertThat(verified.session().locale()).isEqualTo(locale));
    }

    @Test
    void aChangedTokenFails() throws Exception {
        String[] parts = tokens(NOW, KEY).issue(SESSION).token().split("\\.");
        String claims = new String(Base64.getUrlDecoder().decode(parts[1]), UTF_8);
        assertThat(claims).contains("\"ten_A\"");
        String changed = parts[0] + "." + URL.encodeToString(claims.replace("\"ten_A\"", "\"ten_B\"").getBytes(UTF_8)) + "." + parts[2];

        assertThat(tokens(NOW, KEY).verify(changed)).isEmpty();
    }

    @Test
    void aTokenSignedWithAnotherKeyFails() throws Exception {
        assertThat(tokens(NOW, KEY).verify(tokens(NOW, NEXT_KEY).issue(SESSION).token())).isEmpty();
        assertThat(tokens(NOW, KEY).verify(handMade(header("HS256", KEY), claims(""), NEXT_KEY, "HmacSHA256"))).isEmpty();
    }

    @Test
    void anUnsignedTokenFails() throws Exception {
        assertThat(tokens(NOW, KEY).verify(handMade("{\"alg\":\"none\"}", claims(""), null, null))).isEmpty();
        assertThat(tokens(NOW, KEY).verify(handMade("{\"alg\":\"none\",\"kid\":\"" + kid(KEY) + "\"}", claims(""), null, null)))
                .isEmpty();
    }

    /** A 64-byte key could check HS384 and HS512 too, so only the pin to HS256 stops these. */
    @ParameterizedTest
    @ValueSource(strings = {"HS384", "HS512"})
    void anotherHmacAlgorithmFails(String alg) throws Exception {
        byte[] longKey = key(3, 64);
        String mac = "HmacSHA" + alg.substring(2);

        assertThat(tokens(NOW, longKey).verify(handMade(header(alg, longKey), claims(""), longKey, mac))).isEmpty();
        assertThat(tokens(NOW, longKey).verify(handMade(header("HS256", longKey), claims(""), longKey, "HmacSHA256"))).isPresent();
    }

    /** The old confusion attack: an RSA header over an HMAC signature. */
    @Test
    void anRsaHeaderFails() throws Exception {
        assertThat(tokens(NOW, KEY).verify(handMade(header("RS256", KEY), claims(""), KEY, "HmacSHA256"))).isEmpty();
    }

    @Test
    void aTokenWithoutAKeyIdFails() throws Exception {
        assertThat(tokens(NOW, KEY).verify(handMade("{\"alg\":\"HS256\",\"typ\":\"JWT\"}", claims(""), KEY, "HmacSHA256"))).isEmpty();
    }

    @Test
    void aTokenWithAnUnknownCriticalHeaderFails() throws Exception {
        String header = "{\"alg\":\"HS256\",\"kid\":\"" + kid(KEY) + "\",\"crit\":[\"exp-check\"],\"exp-check\":false}";

        assertThat(tokens(NOW, KEY).verify(handMade(header, claims(""), KEY, "HmacSHA256"))).isEmpty();
    }

    @Test
    void aTokenSignedBeforeARotationWorksUntilThePreviousKeyIsDropped() {
        String before = tokens(NOW, KEY).issue(SESSION).token();

        assertThat(tokens(NOW, NEXT_KEY, KEY).verify(before)).isPresent();
        assertThat(tokens(NOW, NEXT_KEY).verify(before)).isEmpty();
    }

    @Test
    void newTokensUseTheNewKey() throws Exception {
        String after = tokens(NOW, NEXT_KEY, KEY).issue(SESSION).token();

        assertThat(SignedJWT.parse(after).getHeader().getKeyID()).isEqualTo(kid(NEXT_KEY));
        assertThat(tokens(NOW, NEXT_KEY).verify(after)).isPresent();
    }

    /** Each one is signed with the right key, so only the claims are wrong. */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"admin\",\"exp\":EXP}",
        "{\"sub\":\"eu_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP}",
        "{\"sub\":\"eu_A\",\"ten\":\"app_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"lvl\":\"verified\",\"exp\":EXP}",
        "{\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP}",
        "{\"sub\":\"usr_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"exp\":EXP}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\"}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":\"later\"}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"scp\":[1,2]}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"scp\":[\"a\",null]}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"ctx\":{\"order\":1001}}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"ctx\":\"order=1001\"}",
        "{\"sub\":\"eu_A\",\"ten\":7,\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"loc\":\"ja-jp\"}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"loc\":\"ja_JP\"}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"loc\":\"\"}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"loc\":7}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"loc\":[\"ja-JP\"]}",
        "{\"sub\":\"eu_A\",\"ten\":\"ten_A\",\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP,\"loc\":\"" + TOO_LONG_LOCALE + "\"}"
    })
    void aTokenWithBadClaimsFails(String claims) throws Exception {
        String token = handMade(header("HS256", KEY), claims.replace("EXP", Long.toString(EXP)), KEY, "HmacSHA256");

        assertThat(tokens(NOW, KEY).verify(token)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "a.b.c", "a.b", "..", "eyJhbGciOiJIUzI1NiJ9..", "not a token at all"})
    void somethingThatIsNotATokenFails(String token) {
        assertThat(tokens(NOW, KEY).verify(token)).isEmpty();
    }

    @Test
    void noTokenFails() {
        assertThat(tokens(NOW, KEY).verify(null)).isEmpty();
    }

    /** The message gives the length found, so whoever set the key sees what is wrong. */
    @Test
    void aKeyShorterThan256BitsIsRefused() {
        assertThatThrownBy(() -> tokens(NOW, key(1, 31)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A session key needs at least 256 bits, and this one has 248");
        assertThatThrownBy(() -> tokens(NOW, KEY, key(2, 16)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A session key needs at least 256 bits, and this one has 128");
    }

    @Test
    void theSameKeyTwiceIsRefused() {
        assertThatThrownBy(() -> tokens(NOW, KEY, KEY.clone())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("twice");
    }

    /** The tokens keep their own copy, so a caller that clears its key array afterwards breaks nothing. */
    @Test
    void theTokensKeepTheirOwnCopyOfTheKeys() {
        byte[] signing = key(4, 32);
        byte[] previous = key(5, 32);
        SessionTokens tokens = tokens(NOW, signing, previous);
        String before = tokens(NOW, key(5, 32)).issue(SESSION).token();
        Arrays.fill(signing, (byte) 0);
        Arrays.fill(previous, (byte) 0);

        assertThat(tokens.verify(tokens.issue(SESSION).token())).isPresent();
        assertThat(tokens.verify(before)).isPresent();
    }

    @Test
    void identityLevelsUseTheContractNames() {
        assertThat(Arrays.stream(IdentityLevel.values()).map(IdentityLevel::wireName)).containsExactly("anonymous", "unverified", "verified");
        assertThat(IdentityLevel.fromWireName("verified")).contains(IdentityLevel.VERIFIED);
        assertThat(IdentityLevel.fromWireName("VERIFIED")).isEmpty();
        assertThat(IdentityLevel.fromWireName(null)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "ja-JP, ja-JP", "ja-jp, ja-JP", "JA-jp, ja-JP", "zh-hans-cn, zh-Hans-CN", "en-us-x-twain, en-US-x-twain",
        "iw, he", "i-klingon, tlh", "es-419, es-419"
    })
    void aLocaleIsWrittenInStandardForm(String tag, String standard) {
        assertThat(SessionTokens.standardLocale(tag)).contains(standard);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "ja_JP", " ja-JP", "ja-JP ", "ja-", "-ja", "ja--JP", "a", "toolongtag", "日本", "ja-JP\n",
        "ja-JP-\u0000", TOO_LONG_LOCALE})
    void aTagThatIsNotWellFormedHasNoStandardForm(String tag) {
        assertThat(SessionTokens.standardLocale(tag)).isEmpty();
    }

    /** The model generated from the contract carries its Locale.maxLength, so this holds the two limits together. */
    @Test
    void theLocaleLimitIsTheContracts() throws Exception {
        Size size = CreateSessionRequest.class.getMethod("getLocale").getAnnotation(Size.class);

        assertThat(size.max()).isEqualTo(SessionTokens.MAX_LOCALE);
    }

    @Test
    void aLocaleMayHaveThirtyFiveCharacters() {
        assertThat(LONGEST_LOCALE).hasSize(SessionTokens.MAX_LOCALE);
        assertThat(TOO_LONG_LOCALE).hasSize(SessionTokens.MAX_LOCALE + 1);

        assertThat(SessionTokens.standardLocale(LONGEST_LOCALE)).contains(LONGEST_LOCALE);
        assertThat(SessionTokens.standardLocale(TOO_LONG_LOCALE)).isEmpty();
        assertThat(SessionTokens.standardLocale(null)).isEmpty();
    }

    /** A session holds a locale only in standard form, so every token writes a tag the check below accepts. */
    @ParameterizedTest
    @ValueSource(strings = {"ja-jp", "iw", "", "ja_JP", " ja-JP", TOO_LONG_LOCALE})
    void aSessionRefusesALocaleThatIsNotInStandardForm(String locale) {
        assertThatThrownBy(() -> new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of(), Map.of(), locale))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A session locale must be a BCP 47 tag in standard form, such as ja-JP");
    }

    /** A session holds only IDs that the check accepts, so issue never signs a token that verify would refuse. */
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
        "tenant, app_A, app_A, eu_A",
        "tenant, ten_, app_A, eu_A",
        "tenant, ten_a-b, app_A, eu_A",
        "tenant, ' ten_A', app_A, eu_A",
        "tenant, null, app_A, eu_A",
        "application, ten_A, app_lower_test, eu_A",
        "application, ten_A, APP_A, eu_A",
        "application, ten_A, null, eu_A",
        "end user, ten_A, app_A, eu-A",
        "end user, ten_A, app_A, eu_A.1",
        "end user, ten_A, app_A, null"
    })
    void aSessionRefusesAnIdOfTheWrongShape(String kind, String tenantId, String applicationId, String endUserId) {
        String wrong = switch (kind) {
            case "tenant" -> tenantId;
            case "application" -> applicationId;
            default -> endUserId;
        };
        String prefix = switch (kind) {
            case "tenant" -> "ten_";
            case "application" -> "app_";
            default -> "eu_";
        };

        assertThatThrownBy(() -> new Session(tenantId, applicationId, endUserId, IdentityLevel.VERIFIED, List.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A session's " + kind + " ID must match " + prefix + "[0-9A-Za-z]{1,60}, and " + wrong + " does not");
    }

    /** An ID has 1 to 60 letters and digits after its prefix; one that Ids makes has 26. Each such session verifies. */
    @Test
    void everyIdASessionTakesComesBackFromItsToken() {
        String sixty = "A".repeat(60);
        Session longest = new Session("ten_" + sixty, "app_" + sixty, "eu_" + sixty, IdentityLevel.VERIFIED, List.of(), Map.of(), null);
        Session real = new Session(Ids.next("ten"), Ids.next("app"), Ids.next("eu"), IdentityLevel.VERIFIED, List.of(), Map.of(), null);
        SessionTokens tokens = tokens(NOW, KEY);

        for (Session session : List.of(longest, real, SESSION)) {
            assertThat(tokens.verify(tokens.issue(session).token())).hasValueSatisfying(verified -> assertThat(verified.session()).isEqualTo(session));
        }
        assertThatThrownBy(() -> new Session("ten_A", "app_A", "eu_" + sixty + "A", IdentityLevel.VERIFIED, List.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The count is what the token carries, escapes included: adding scp and ctx to a token adds the count, plus the
     * 7 bytes of each {@code ,"scp":} and {@code ,"ctx":}. The values hold what the library escapes. By hand, the
     * scopes take 26 bytes, because U+2028 is written as 6. The context takes 73. Its note takes 45: 6 each for
     * U+0001, U+2028 and U+2029; 2 each for the quote, backslash, line feed, tab and é; 3 each for 日 and 本; 4 for the
     * emoji; 1 each for the other six.
     */
    @Test
    void claimsBytesAreWhatTheTokenCarries() throws Exception {
        List<String> scopes = List.of("orders:read", "a b");
        Map<String, String> context = Map.of("note", "<>&='\"\\/\u0001\n\t\u007f  é日本😀", "order", "A-1001");
        Session bare = new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of(), Map.of(), null);
        Session full = new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, scopes, context, null);
        SessionTokens tokens = tokens(NOW, KEY);

        int added = payloadBytes(tokens.issue(full).token()) - payloadBytes(tokens.issue(bare).token());

        assertThat(tokens.claimsBytes(scopes, context)).isEqualTo(added - 14).isEqualTo(26 + 73);
        assertThat(tokens.claimsBytes(scopes, Map.of()) + tokens.claimsBytes(List.of(), context)).isEqualTo(added - 14);
    }

    @Test
    void emptyScopesAndContextTakeNoBytes() {
        assertThat(tokens(NOW, KEY).claimsBytes(List.of(), Map.of())).isZero();
    }

    /** Every token stays near 3 KB, whoever asks for it, so the limit holds here too and not only in the API. */
    @Test
    void aTokenCarriesAtMost2048BytesOfScopesAndContext() {
        Map<String, String> context = new HashMap<>();
        for (int i = 1; i <= 9; i++) {
            context.put("k" + i, "v".repeat(200));
        }
        context.put("k0", "v".repeat(167));
        Map<String, String> oneMore = new HashMap<>(context);
        oneMore.put("k0", "v".repeat(168));
        SessionTokens tokens = tokens(NOW, KEY);
        assertThat(tokens.claimsBytes(List.of(), context)).isEqualTo(SessionTokens.MAX_CLAIMS_BYTES);

        assertThat(tokens.issue(new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of(), context, null)).token())
                .hasSizeLessThan(3100);
        assertThatThrownBy(() -> tokens.issue(new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of(), oneMore, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Scopes and context take 2049 bytes, and a token allows at most 2048");
    }

    private static int payloadBytes(String token) {
        return Base64.getUrlDecoder().decode(token.split("\\.")[1]).length;
    }
}

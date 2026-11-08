package aie.app;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.tenancy.api.SessionTokens;
import aie.core.tenancy.api.SessionTokens.IdentityLevel;
import aie.core.tenancy.api.SessionTokens.Issued;
import aie.core.tenancy.api.SessionTokens.Session;
import aie.core.tenancy.api.SessionTokens.Verified;
import aie.core.tenancy.internal.SignedSessionTokens;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jwt.SignedJWT;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
            "ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of("orders.read"), Map.of("order", "A-1001"));
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

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
    void aSessionWithoutScopesOrContextComesBackEmpty() {
        Session bare = new Session("ten_A", "app_A", "eu_A", IdentityLevel.UNVERIFIED, List.of(), Map.of());

        assertThat(tokens(NOW, KEY).verify(tokens(NOW, KEY).issue(bare).token()))
                .hasValueSatisfying(verified -> assertThat(verified.session()).isEqualTo(bare));
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
                new Session("ten_A", "app_A", "eu_A", IdentityLevel.VERIFIED, List.of(), Map.of()), Instant.ofEpochSecond(EXP)));
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
        "{\"sub\":\"eu_A\",\"ten\":7,\"app\":\"app_A\",\"lvl\":\"verified\",\"exp\":EXP}"
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
}

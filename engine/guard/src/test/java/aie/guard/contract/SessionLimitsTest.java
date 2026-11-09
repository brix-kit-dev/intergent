package aie.guard.contract;

import static aie.guard.contract.Contract.list;
import static aie.guard.contract.Contract.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The limits of {@code POST /v1/sessions} in the real contract. Scopes and context travel inside the token, and the
 * browser sends the token in a request header, which many servers cap at 8 KB. A-7 already stops these limits from
 * tightening. This test stops them from widening, which A-7 allows, because a wider limit lets the token outgrow the
 * header. The owner approved these numbers on 2026-11-09.
 */
class SessionLimitsTest {

    /** Scope names and context keys: letters, digits and {@code _ . : -}. */
    private static final String NAME = "^[A-Za-z0-9_.:-]+$";

    private static Contract contract;

    @BeforeAll
    static void readTheContract() throws IOException, URISyntaxException {
        Path testClasses = Path.of(SessionLimitsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        contract = Contract.read(testClasses.getParent().getParent().getParent().getParent().resolve("contracts/openapi.yaml"));
    }

    @Test
    void scopesAreAFewShortNames() {
        Map<String, Object> scopes = property("CreateSessionRequest", "scopes");

        assertThat(scopes).containsEntry("maxItems", 20);
        assertThat(map(scopes.get("items")))
                .containsEntry("type", "string")
                .containsEntry("minLength", 1)
                .containsEntry("maxLength", 64)
                .containsEntry("pattern", NAME);
    }

    @Test
    void contextIsAFewShortEntries() {
        Map<String, Object> context = property("CreateSessionRequest", "context");

        assertThat(context).containsEntry("maxProperties", 10);
        assertThat(map(context.get("propertyNames")))
                .containsEntry("minLength", 1)
                .containsEntry("maxLength", 40)
                .containsEntry("pattern", NAME);
        assertThat(map(context.get("additionalProperties")))
                .containsEntry("type", "string")
                .containsEntry("maxLength", 200);
    }

    /** JSON Schema cannot say this, so the description does, and the engine checks it. */
    @Test
    void theRequestStatesTheLimitOfTheWhole() {
        String description = (String) schema("CreateSessionRequest").get("description");

        assertThat(description.replaceAll("\\s+", " "))
                .contains("together they may take at most 2048 bytes of compact UTF-8 JSON")
                .contains("`invalid_request`");
    }

    /** The engine never stores a token, so a replay of the same Idempotency-Key has none to give. */
    @Test
    void aReplayMayLeaveTheTokenOut() {
        assertThat(list(schema("Session").get("required")))
                .containsExactlyInAnyOrder("expires_at", "end_user_id", "identity_level");
        assertThat(map(schema("Session").get("properties"))).containsKey("token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"orders:read", "order_id", "faq", "a.b-c", "A1", "x", "Order.Read:2"})
    void aNameMayUseLettersDigitsAndFourMarks(String name) {
        assertThat(Pattern.compile(NAME).matcher(name).matches()).isTrue();
    }

    /** {@code a/b} catches a character range such as {@code .-:} in place of the plain marks. */
    @ParameterizedTest
    @ValueSource(strings = {"", "a b", "a/b", "a,b", "a;b", "a\"b", "a\\b", "a+b", "a\nb", "a\n", "é", "注文", "a\u0000"})
    void aNameRefusesEverythingElse(String name) {
        assertThat(Pattern.compile(NAME).matcher(name).matches()).isFalse();
    }

    private static Map<String, Object> property(String schema, String property) {
        return contract.flatten(map(map(schema(schema).get("properties")).get(property)));
    }

    private static Map<String, Object> schema(String name) {
        return contract.flatten(map(map(map(contract.root().get("components")).get("schemas")).get(name)));
    }
}

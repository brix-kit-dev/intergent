package aie.guard.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContractRulesTest {

    private static final String CREATE_SESSION = "operationId: createSession\n      x-callers: [server]\n";
    private static final String IDEMPOTENCY = "      parameters:\n        - $ref: \"#/components/parameters/IdempotencyKey\"\n      requestBody:";

    private static String legal;

    @BeforeAll
    static void readTheSample() throws IOException {
        try (InputStream sample = ContractRulesTest.class.getResourceAsStream("/contract/legal.yaml")) {
            legal = new String(sample.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String replaced(String from, String to) {
        assertThat(legal).contains(from);
        return legal.replace(from, to);
    }

    private static List<String> violations(String from, String to) {
        return ContractRules.violations(replaced(from, to));
    }

    @Test
    void theLegalContractPasses() {
        assertThat(ContractRules.violations(legal)).isEmpty();
    }

    @Test
    void anOperationWithoutCallersFails() {
        assertThat(violations(CREATE_SESSION, "operationId: createSession\n")).singleElement().asString().contains("S-10", "POST /v1/sessions");
    }

    @ParameterizedTest
    @CsvSource({"get", "put", "post", "delete", "patch"})
    void everyMethodIsChecked(String method) {
        String added = """
                  /v1/extra:
                    %s:
                      operationId: extra
                      responses: {}
                  /v1/anonymous-sessions:
                """.formatted(method);

        assertThat(violations("  /v1/anonymous-sessions:\n", added))
                .anySatisfy(problem -> assertThat(problem).contains("S-10", method.toUpperCase(Locale.ROOT) + " /v1/extra"));
    }

    @Test
    void thePublicOperationMustAlsoDeclareItsCallers() {
        assertThat(violations("      x-callers: [browser]\n", "")).singleElement().asString().contains("S-10", "must declare");
    }

    @Test
    void anUnknownCallerBesideAKnownOneFails() {
        assertThat(violations(CREATE_SESSION, "operationId: createSession\n      x-callers: [server, partner]\n"))
                .singleElement().asString().contains("S-10", "must declare");
    }

    @Test
    void anUnknownCallerFails() {
        assertThat(violations(CREATE_SESSION, "operationId: createSession\n      x-callers: [partner]\n")).singleElement().asString().contains("S-10");
    }

    @Test
    void aBrowserTokenOnAServerOperationFails() {
        assertThat(violations(CREATE_SESSION, CREATE_SESSION + "      security:\n        - apiKey: []\n        - sessionToken: []\n"))
                .singleElement().asString().contains("S-10", "sessionToken");
    }

    @Test
    void aBrowserCallerWithoutASessionTokenFails() {
        assertThat(violations("        - apiKey: []\n        - sessionToken: []\n", "        - apiKey: []\n"))
                .singleElement().asString().contains("S-10", "sessionToken");
    }

    @Test
    void aServerCallerWithoutAnApiKeyFails() {
        assertThat(violations("        - apiKey: []\n        - sessionToken: []\n", "        - sessionToken: []\n"))
                .singleElement().asString().contains("S-10", "apiKey");
    }

    @Test
    void onlyTheAnonymousSessionMayGoWithoutACredential() {
        assertThat(violations(CREATE_SESSION, CREATE_SESSION + "      security: []\n"))
                .singleElement().asString().contains("S-10", "must require a credential");
    }

    @Test
    void aPostWithoutAnIdempotencyKeyFails() {
        assertThat(violations(IDEMPOTENCY, "      requestBody:")).singleElement().asString().contains("A-8", "POST /v1/sessions");
    }

    @ParameterizedTest
    @CsvSource({"/v1/chat", "/v1/chat/completions", "/v1/completions", "/v1/embeddings", "/v1/models", "/v1/models/{id}", "/v1/generate"})
    void aModelPathFails(String path) {
        assertThat(violations("/v1/sessions:", path + ":")).singleElement().asString().contains("A-11");
    }

    @ParameterizedTest
    @CsvSource({"model", "model_id", "temperature", "top_p", "max_tokens", "system", "prompt"})
    void aModelSettingInTheBodyFails(String field) {
        assertThat(violations("        tier:\n", "        " + field + ":\n"))
                .singleElement().asString().contains("A-11", field);
    }

    @Test
    void aModelSettingDeepInTheBodyFails() {
        assertThat(violations("        notes:\n          type: object\n",
                        "        notes:\n          type: object\n          properties:\n            model:\n              type: string\n"))
                .singleElement().asString().contains("A-11", "model");
    }

    @Test
    void aModelSettingInsideAMapFails() {
        assertThat(violations("          additionalProperties:\n            type: string\n            maxLength: 100\n",
                        "          additionalProperties:\n            type: object\n            properties:\n              model:\n                type: string\n"))
                .singleElement().asString().contains("A-11", "model");
    }

    @Test
    void aModelSettingAsAQueryParameterFails() {
        assertThat(violations("        - name: limit\n", "        - name: model\n"))
                .contains("A-11: GET /v1/conversations/{conversation_id}/messages takes the model setting 'model'");
    }

    @Test
    void aPathOutsideV1Fails() {
        assertThat(violations("/v1/sessions:", "/sessions:")).singleElement().asString().contains("6.2", "/v1/");
    }

    @Test
    void aMissingVersionHeaderFails() {
        assertThat(violations("""
                  /v1/sessions:
                    parameters:
                      - $ref: "#/components/parameters/EngineVersion"
                """, """
                  /v1/sessions:
                    parameters:
                """)).singleElement().asString().contains("6.2", "Engine-Version");
    }

    @Test
    void aMissingTraceHeaderFails() {
        assertThat(violations("""
                  /v1/sessions:
                    parameters:
                      - $ref: "#/components/parameters/EngineVersion"
                      - $ref: "#/components/parameters/Traceparent"
                """, """
                  /v1/sessions:
                    parameters:
                      - $ref: "#/components/parameters/EngineVersion"
                """)).singleElement().asString().contains("6.2", "traceparent");
    }

    @Test
    void aResponseWithoutRequestIdFails() {
        assertThat(violations("""
                          description: A page.
                          headers:
                            Request-Id:
                              $ref: "#/components/headers/RequestId"
                """, """
                          description: A page.
                          headers:
                """)).singleElement().asString().contains("6.2", "Request-Id", "200");
    }

    @Test
    void anErrorThatIsNotAProblemFails() {
        assertThat(violations("""
                        application/problem+json:
                          schema:
                            $ref: "#/components/schemas/Problem"
                    RateLimited:
                """, """
                        application/json:
                          schema:
                            $ref: "#/components/schemas/Problem"
                    RateLimited:
                """)).singleElement().asString().contains("6.2", "400", "application/problem+json");
    }

    @Test
    void anErrorWithASecondFormatFails() {
        assertThat(violations("""
                        application/problem+json:
                          schema:
                            $ref: "#/components/schemas/Problem"
                    RateLimited:
                """, """
                        application/problem+json:
                          schema:
                            $ref: "#/components/schemas/Problem"
                        application/json:
                          schema:
                            $ref: "#/components/schemas/Problem"
                    RateLimited:
                """)).singleElement().asString().contains("6.2", "application/problem+json");
    }

    @Test
    void aRateLimitWithoutRetryAfterFails() {
        assertThat(violations("""
                        Retry-After:
                          $ref: "#/components/headers/RetryAfter"
                """, "")).singleElement().asString().contains("6.10", "Retry-After");
    }

    @Test
    void aMissingErrorCodeFails() {
        assertThat(violations("        - stream_interrupted\n", "")).singleElement().asString().contains("6.10");
    }

    @Test
    void anExtraErrorCodeFails() {
        assertThat(violations("        - stream_interrupted\n", "        - stream_interrupted\n        - teapot\n"))
                .singleElement().asString().contains("6.10");
    }

    @Test
    void aMissingEventFails() {
        assertThat(violations("  tool.started:\n    $ref: \"#/components/schemas/Message\"\n", ""))
                .singleElement().asString().contains("6.4");
    }

    @Test
    void anExtraEventFails() {
        assertThat(violations("  error:\n", "  ping:\n    $ref: \"#/components/schemas/Message\"\n  error:\n"))
                .singleElement().asString().contains("6.4");
    }

    @Test
    void aClosedEnumInAResponseFails() {
        assertThat(violations("x-extensible-enum: [anonymous, verified]", "enum: [anonymous, verified]"))
                .singleElement().asString().contains("6.2", "closed enum");
    }

    @Test
    void aClosedEnumInAnEventFails() {
        assertThat(violations("    DeltaEvent:\n      type: object\n      required: [text]\n      properties:\n        text:\n          type: string\n",
                        "    DeltaEvent:\n      type: object\n      required: [text]\n      properties:\n        text:\n          type: string\n          enum: [a, b]\n"))
                .singleElement().asString().contains("6.2", "event message.delta", "closed enum");
    }

    @Test
    void aClosedEnumInARequestPasses() {
        assertThat(legal).contains("enum: [free, paid]");
        assertThat(ContractRules.violations(legal)).isEmpty();
    }

    @Test
    void aListWithoutCursorPagingFails() {
        assertThat(violations("        - name: cursor\n          in: query\n          schema:\n            type: string\n", ""))
                .singleElement().asString().contains("6.2", "cursor and limit");
    }

    @Test
    void aListWithoutALimitFails() {
        assertThat(violations("        - name: limit\n          in: query\n          schema:\n            type: integer\n            maximum: 100\n", ""))
                .singleElement().asString().contains("6.2", "cursor and limit");
    }

    @Test
    void pageNumbersFail() {
        assertThat(violations("        - name: cursor\n", "        - name: cursor\n          in: query\n          schema:\n            type: string\n        - name: page\n"))
                .singleElement().asString().contains("6.2", "cursors");
    }

    @Test
    void aFieldNotInSnakeCaseFails() {
        assertThat(violations("        token:\n", "        accessToken:\n")).anySatisfy(problem -> assertThat(problem).contains("6.2", "snake_case"));
    }

    @Test
    void aTimeThatIsNotADateTimeFails() {
        assertThat(violations("          type: string\n          format: date-time\n", "          type: integer\n"))
                .singleElement().asString().contains("6.2", "date-time");
    }

    @Test
    void anIdWithoutATypePrefixFails() {
        assertThat(violations("      pattern: \"^conv_[0-9A-Za-z]+$\"\n", "      pattern: \"^[0-9]+$\"\n"))
                .singleElement().asString().contains("6.2", "type prefix");
    }

    @Test
    void anIdWithoutAnyPatternFails() {
        assertThat(violations("      pattern: \"^conv_[0-9A-Za-z]+$\"\n", "")).singleElement().asString().contains("6.2", "type prefix");
    }

    @Test
    void aVersionThatIsNotADateFails() {
        assertThat(violations("version: \"2026-11-05\"", "version: \"1.0\"")).singleElement().asString().contains("6.2", "date version");
    }

    @Test
    void aMissingOperationIdFails() {
        assertThat(violations("      operationId: listMessages\n", "")).singleElement().asString().contains("6.2", "operationId");
    }

    @Test
    void aRepeatedOperationIdFails() {
        assertThat(violations("operationId: listMessages", "operationId: createSession")).singleElement().asString().contains("6.2", "operationId");
    }

    @Test
    void aReferenceToAnotherFileIsRefused() {
        assertThatThrownBy(() -> violations("$ref: \"#/components/schemas/Session\"", "$ref: \"other.yaml#/Session\""))
                .hasMessageContaining("local references");
    }

    @Test
    void aReferenceToNothingIsRefused() {
        assertThatThrownBy(() -> violations("$ref: \"#/components/schemas/Session\"", "$ref: \"#/components/schemas/Gone\""))
                .hasMessageContaining("points at nothing");
    }

    @Test
    void aReferenceLoopIsRefused() {
        assertThatThrownBy(() -> violations("    Session:\n      type: object\n", "    Session:\n      $ref: \"#/components/schemas/Session\"\n      type: object\n"))
                .hasMessageContaining("loop");
    }

    @Test
    void theProductNameFails() {
        assertThat(violations("title: Sample", "title: Intergent API")).singleElement().asString().contains("Q-3");
    }
}

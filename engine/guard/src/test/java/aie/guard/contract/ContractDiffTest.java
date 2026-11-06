package aie.guard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ContractDiffTest {

    private static String legal;

    @BeforeAll
    static void readTheSample() throws IOException {
        try (InputStream sample = ContractDiffTest.class.getResourceAsStream("/contract/legal.yaml")) {
            legal = new String(sample.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> changes(String from, String to) {
        assertThat(legal).contains(from);
        return ContractDiff.breakingChanges(Contract.parse(legal), Contract.parse(legal.replace(from, to)));
    }

    /** The same replacement the other way round: the replaced text is the baseline. */
    private static List<String> reversed(String from, String to) {
        assertThat(legal).contains(from);
        return ContractDiff.breakingChanges(Contract.parse(legal.replace(from, to)), Contract.parse(legal));
    }

    @Test
    void theSameContractHasNoChanges() {
        assertThat(ContractDiff.breakingChanges(Contract.parse(legal), Contract.parse(legal))).isEmpty();
    }

    @Test
    void aRemovedDeleteOperationIsBreaking() {
        assertThat(changes("/v1/api-keys/{key_id}:", "/v1/keys/{key_id}:"))
                .singleElement().asString().contains("A-7", "removed operation DELETE /v1/api-keys/{key_id}");
    }

    @Test
    void aRemovedOperationIsBreaking() {
        assertThat(changes("/v1/sessions:", "/v1/login:")).singleElement().asString().contains("A-7", "removed operation POST /v1/sessions");
    }

    @Test
    void aNewOperationIsNotBreaking() {
        assertThat(changes("  /v1/anonymous-sessions:\n", """
                  /v1/extra:
                    get:
                      operationId: extra
                      responses: {}
                  /v1/anonymous-sessions:
                """)).isEmpty();
    }

    @Test
    void aRemovedCallerIsBreaking() {
        assertThat(changes("x-callers: [server, browser]", "x-callers: [server]"))
                .singleElement().asString().contains("A-7", "no longer accepts caller browser");
    }

    @Test
    void aNewCallerIsNotBreaking() {
        assertThat(changes("x-callers: [server]", "x-callers: [server, browser]")).isEmpty();
    }

    @Test
    void aRemovedParameterIsBreaking() {
        assertThat(changes("        - name: cursor\n          in: query\n          schema:\n            type: string\n", ""))
                .singleElement().asString().contains("A-7", "removed parameter query:cursor");
    }

    @Test
    void aRenamedParameterIsBreaking() {
        assertThat(changes("- name: cursor", "- name: after")).singleElement().asString().contains("removed parameter query:cursor");
    }

    @Test
    void aParameterThatBecomesRequiredIsBreaking() {
        assertThat(changes("        - name: cursor\n          in: query\n", "        - name: cursor\n          in: query\n          required: true\n"))
                .singleElement().asString().contains("A-7", "made parameter query:cursor required");
    }

    @Test
    void aNewRequiredParameterIsBreaking() {
        assertThat(changes("        - name: limit\n", "        - name: must\n          in: query\n          required: true\n          schema:\n            type: string\n        - name: limit\n"))
                .singleElement().asString().contains("A-7", "added required parameter query:must");
    }

    @Test
    void aNewOptionalParameterIsNotBreaking() {
        assertThat(changes("        - name: limit\n", "        - name: extra\n          in: query\n          schema:\n            type: string\n        - name: limit\n")).isEmpty();
    }

    @Test
    void aRequestBodyThatBecomesRequiredIsBreaking() {
        assertThat(changes("      requestBody:\n        required: true\n", "      requestBody:\n        required: false\n"))
                .isEmpty();
        assertThat(reversed("      requestBody:\n        required: true\n", "      requestBody:\n        required: false\n"))
                .singleElement().asString().contains("A-7", "now requires a request body");
    }

    @Test
    void aRemovedRequestMediaTypeIsBreaking() {
        assertThat(changes("      requestBody:\n        required: true\n        content:\n          application/json:", "      requestBody:\n        required: true\n        content:\n          text/plain:"))
                .singleElement().asString().contains("A-7", "no longer accepts application/json");
    }

    @Test
    void aRemovedRequestFieldIsBreaking() {
        assertThat(changes("        tier:\n", "        plan:\n")).singleElement().asString().contains("A-7", "body.tier was removed");
    }

    @Test
    void aNewOptionalRequestFieldIsNotBreaking() {
        assertThat(changes("        tier:\n", "        extra:\n          type: string\n        tier:\n")).isEmpty();
    }

    @Test
    void aNewRequiredRequestFieldIsBreaking() {
        assertThat(changes("required: [external_user_id]", "required: [external_user_id, tier]"))
                .singleElement().asString().contains("A-7", "body.tier became required");
    }

    @Test
    void aRequiredRequestFieldThatBecomesOptionalIsNotBreaking() {
        assertThat(changes("required: [external_user_id]", "required: []")).isEmpty();
    }

    @Test
    void aRemovedRequestEnumValueIsBreaking() {
        assertThat(changes("enum: [free, paid]", "enum: [free]")).singleElement().asString().contains("A-7", "removed value paid");
    }

    @Test
    void aNewRequestEnumValueIsNotBreaking() {
        assertThat(changes("enum: [free, paid]", "enum: [free, paid, team]")).isEmpty();
    }

    @Test
    void closingARequestValueListIsBreaking() {
        assertThat(reversed("          enum: [free, paid]\n", "")).singleElement().asString().contains("A-7", "closed its list of values");
    }

    @Test
    void aTightenedLengthIsBreaking() {
        assertThat(changes("maxLength: 255", "maxLength: 100")).singleElement().asString().contains("A-7", "tightened maxLength");
    }

    @Test
    void aWiderLengthIsNotBreaking() {
        assertThat(changes("maxLength: 255", "maxLength: 500")).isEmpty();
    }

    @Test
    void aNewLimitIsBreaking() {
        assertThat(changes("        labels:\n          type: array\n", "        labels:\n          type: array\n          minItems: 1\n"))
                .singleElement().asString().contains("A-7", "tightened minItems");
    }

    @Test
    void aTightenedParameterLimitIsBreaking() {
        assertThat(changes("maximum: 100", "maximum: 50")).singleElement().asString().contains("A-7", "tightened maximum");
    }

    @Test
    void aTightenedItemLimitIsBreaking() {
        assertThat(changes("maxItems: 10", "maxItems: 5")).singleElement().asString().contains("A-7", "body.labels tightened maxItems");
    }

    @Test
    void aNewLimitOnArrayItemsIsBreaking() {
        assertThat(changes("          maxItems: 10\n          items:\n            type: string\n", "          maxItems: 10\n          items:\n            type: string\n            maxLength: 5\n"))
                .singleElement().asString().contains("A-7", "body.labels[] tightened maxLength");
    }

    @Test
    void aNewMaxPropertiesIsBreaking() {
        assertThat(changes("        notes:\n          type: object\n", "        notes:\n          type: object\n          maxProperties: 5\n"))
                .singleElement().asString().contains("A-7", "tightened maxProperties");
    }

    @Test
    void aNewMinPropertiesIsBreaking() {
        assertThat(changes("        notes:\n          type: object\n", "        notes:\n          type: object\n          minProperties: 1\n"))
                .singleElement().asString().contains("A-7", "tightened minProperties");
    }

    @Test
    void aNewMinLengthIsBreaking() {
        assertThat(changes("      type: string\n      maxLength: 255\n", "      type: string\n      maxLength: 255\n      minLength: 2\n"))
                .singleElement().asString().contains("A-7", "tightened minLength");
    }

    @Test
    void aTighterResponseLimitIsNotBreaking() {
        assertThat(changes("        token:\n          type: string\n", "        token:\n          type: string\n          maxLength: 10\n")).isEmpty();
    }

    @Test
    void aTightenedMapValueIsBreaking() {
        assertThat(changes("maxLength: 100", "maxLength: 50")).singleElement().asString().contains("A-7", "tightened maxLength");
    }

    @Test
    void aLimitBesideAnAllOfCounts() {
        assertThat(changes("          description: Whose session.\n", "          description: Whose session.\n          maxLength: 10\n"))
                .singleElement().asString().contains("A-7", "tightened maxLength");
    }

    @Test
    void aRaisedMinimumIsBreaking() {
        assertThat(changes("            maximum: 100\n", "            maximum: 100\n            minimum: 5\n"))
                .singleElement().asString().contains("A-7", "tightened minimum");
    }

    @Test
    void aLoweredMinimumIsNotBreaking() {
        String withMinimum = legal.replace("            maximum: 100\n", "            maximum: 100\n            minimum: 5\n");
        String lowered = legal.replace("            maximum: 100\n", "            maximum: 100\n            minimum: 1\n");

        assertThat(ContractDiff.breakingChanges(Contract.parse(withMinimum), Contract.parse(lowered))).isEmpty();
    }

    @Test
    void aNewPatternIsBreaking() {
        assertThat(changes("      type: string\n      maxLength: 255\n", "      type: string\n      maxLength: 255\n      pattern: \"^[a-z]+$\"\n"))
                .singleElement().asString().contains("A-7", "changed its pattern");
    }

    @Test
    void aRemovedResponseIsBreaking() {
        assertThat(changes("        \"429\":\n          $ref: \"#/components/responses/RateLimited\"\n", ""))
                .singleElement().asString().contains("A-7", "removed response 429");
    }

    @Test
    void aNewResponseIsNotBreaking() {
        assertThat(changes("        \"429\":\n          $ref: \"#/components/responses/RateLimited\"\n", "        \"429\":\n          $ref: \"#/components/responses/RateLimited\"\n        \"503\":\n          $ref: \"#/components/responses/BadRequest\"\n")).isEmpty();
    }

    @Test
    void aRemovedResponseHeaderIsBreaking() {
        assertThat(changes("            X-Page-Count:\n              schema:\n                type: integer\n", ""))
                .singleElement().asString().contains("A-7", "removed header X-Page-Count");
    }

    @Test
    void aRemovedResponseMediaTypeIsBreaking() {
        assertThat(changes("""
                          content:
                            application/json:
                              schema:
                                $ref: "#/components/schemas/MessageList"
                """, """
                          content:
                            text/plain:
                              schema:
                                $ref: "#/components/schemas/MessageList"
                """)).singleElement().asString().contains("A-7", "removed application/json");
    }

    @Test
    void aRemovedResponseFieldIsBreaking() {
        assertThat(changes("        level:\n", "        tier:\n")).singleElement().asString().contains("A-7", ".level was removed");
    }

    @Test
    void aNewResponseFieldIsNotBreaking() {
        assertThat(changes("        level:\n", "        extra:\n          type: string\n        level:\n")).isEmpty();
    }

    @Test
    void aResponseFieldThatMayGoMissingIsBreaking() {
        assertThat(changes("required: [token, expires_at]", "required: [token]"))
                .singleElement().asString().contains("A-7", "expires_at is no longer always there");
    }

    @Test
    void aNewlyRequiredResponseFieldIsNotBreaking() {
        assertThat(changes("required: [token, expires_at]", "required: [token, expires_at, level]")).isEmpty();
    }

    @Test
    void aChangedResponseTypeIsBreaking() {
        assertThat(changes("        token:\n          type: string\n", "        token:\n          type: integer\n"))
                .singleElement().asString().contains("A-7", ".token changed type");
    }

    @Test
    void aResponseFieldThatMayBecomeNullIsBreaking() {
        assertThat(changes("        token:\n          type: string\n", "        token:\n          type: [string, \"null\"]\n"))
                .singleElement().asString().contains("A-7", ".token changed type");
    }

    @Test
    void aResponseFieldThatCanNoLongerBeNullIsNotBreaking() {
        assertThat(changes("        next_cursor:\n          type: [string, \"null\"]\n", "        next_cursor:\n          type: string\n")).isEmpty();
    }

    @Test
    void aRequestFieldThatAlsoAcceptsNullIsNotBreaking() {
        assertThat(changes("        tier:\n          type: string\n", "        tier:\n          type: [string, \"null\"]\n")).isEmpty();
    }

    @Test
    void aRequestThatStopsAcceptingNullIsBreaking() {
        assertThat(reversed("        tier:\n          type: string\n", "        tier:\n          type: [string, \"null\"]\n"))
                .singleElement().asString().contains("A-7", "changed type");
    }

    @Test
    void aChangedFormatIsBreaking() {
        assertThat(changes("format: date-time", "format: date")).singleElement().asString().contains("A-7", "changed format");
    }

    @Test
    void aRemovedExtensibleValueIsBreaking() {
        assertThat(changes("x-extensible-enum: [anonymous, verified]", "x-extensible-enum: [verified]"))
                .singleElement().asString().contains("A-7", "removed value anonymous");
    }

    @Test
    void aNewExtensibleValueIsNotBreaking() {
        assertThat(changes("x-extensible-enum: [anonymous, verified]", "x-extensible-enum: [anonymous, unverified, verified]")).isEmpty();
    }

    @Test
    void aRemovedErrorCodeIsBreaking() {
        assertThat(changes("        - stream_interrupted\n", "")).isNotEmpty()
                .allSatisfy(problem -> assertThat(problem).contains("A-7", "removed value stream_interrupted"));
    }

    @Test
    void aRemovedEventIsBreaking() {
        assertThat(changes("  tool.started:\n    $ref: \"#/components/schemas/Message\"\n", ""))
                .singleElement().asString().contains("A-7", "removed event tool.started");
    }

    @Test
    void aNewEventIsNotBreaking() {
        assertThat(changes("  error:\n", "  ping:\n    $ref: \"#/components/schemas/Message\"\n  error:\n")).isEmpty();
    }

    @Test
    void aChangedEventFieldIsBreaking() {
        assertThat(changes("      required: [text]\n      properties:\n        text:\n          type: string\n", "      required: [text]\n      properties:\n        text:\n          type: integer\n"))
                .singleElement().asString().contains("A-7", "event message.delta.text changed type");
    }

    @Test
    void aSchemaThatRefersToItselfEnds() {
        assertThat(legal).contains("$ref: \"#/components/schemas/Message\"");
        assertThat(ContractDiff.breakingChanges(Contract.parse(legal), Contract.parse(legal))).isEmpty();
    }
}

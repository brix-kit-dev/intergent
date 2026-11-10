package aie.guard.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
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

    private static final String NOTES = "        notes:\n          type: object\n";

    /** The sample's notes map with these rules for its key names, one rule per line. */
    private static String notesWithKeys(String rules) {
        return NOTES + "          propertyNames:\n" + rules.lines().map(rule -> "            " + rule + "\n").collect(Collectors.joining());
    }

    /** The sample with the notes map as {@code from} is the baseline; the sample with it as {@code to} is current. */
    private static List<String> notes(String from, String to) {
        return ContractDiff.breakingChanges(Contract.parse(legal.replace(NOTES, from)), Contract.parse(legal.replace(NOTES, to)));
    }

    @Test
    void aTightenedKeyNameLengthIsBreaking() {
        assertThat(notes(notesWithKeys("maxLength: 40"), notesWithKeys("maxLength: 20")))
                .singleElement().asString().contains("A-7", "body.notes property names tightened maxLength");
    }

    @Test
    void aNewKeyNameRuleIsBreaking() {
        assertThat(changes(NOTES, notesWithKeys("maxLength: 40")))
                .singleElement().asString().contains("A-7", "body.notes property names tightened maxLength");
    }

    @Test
    void aRaisedKeyNameMinimumIsBreaking() {
        assertThat(notes(notesWithKeys("minLength: 1"), notesWithKeys("minLength: 2")))
                .singleElement().asString().contains("A-7", "body.notes property names tightened minLength");
    }

    @Test
    void aChangedKeyNamePatternIsBreaking() {
        assertThat(notes(notesWithKeys("pattern: \"^[a-z_]+$\""), notesWithKeys("pattern: \"^[a-z]+$\"")))
                .singleElement().asString().contains("A-7", "body.notes property names changed its pattern");
    }

    @Test
    void aClosedListOfKeyNamesIsBreaking() {
        assertThat(changes(NOTES, notesWithKeys("enum: [order_id, plan]")))
                .singleElement().asString().contains("A-7", "body.notes property names closed its list of values");
    }

    @Test
    void aWiderOrDroppedKeyNameRuleIsNotBreaking() {
        assertThat(notes(notesWithKeys("maxLength: 40"), notesWithKeys("maxLength: 80"))).isEmpty();
        assertThat(notes(notesWithKeys("maxLength: 40"), NOTES)).isEmpty();
    }

    @Test
    void aTighterKeyNameRuleInAResponseIsNotBreaking() {
        String token = "        token:\n          type: string\n";
        String meta = token + "        meta:\n          type: object\n          additionalProperties:\n            type: string\n";
        String before = legal.replace(token, meta);
        String after = legal.replace(token, meta + "          propertyNames:\n            maxLength: 10\n");

        assertThat(ContractDiff.breakingChanges(Contract.parse(before), Contract.parse(after))).isEmpty();
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

    // Code point 30: a rule that is not written takes anything, so writing one where there was none is a change too.

    private static final String REQUEST = "    CreateSessionRequest:\n      type: object\n";
    private static final String RESPONSE = "    Session:\n      type: object\n";
    private static final String TOKEN = "        token:\n          type: string\n";
    private static final String NOTE_VALUES = "          additionalProperties:\n            type: string\n            maxLength: 100\n";
    private static final String LABEL_ITEMS = "          maxItems: 10\n          items:\n            type: string\n";
    private static final String NO_LABEL_ITEMS = "          maxItems: 10\n";

    /** The sample with {@code from} after the session's token is the baseline; with {@code to} there, it is current. */
    private static List<String> sessionFields(String from, String to) {
        return ContractDiff.breakingChanges(Contract.parse(legal.replace(TOKEN, TOKEN + from)), Contract.parse(legal.replace(TOKEN, TOKEN + to)));
    }

    @Test
    void aTypeWhereThereWasNoneIsBreakingInARequest() {
        assertThat(reversed("            type: string\n            maxLength: 100\n", "            maxLength: 100\n"))
                .containsExactly("A-7: POST /v1/sessions body.notes{} changed type from any to [string]");
    }

    @Test
    void aRequestValueThatDropsItsTypeIsNotBreaking() {
        assertThat(changes("            type: string\n            maxLength: 100\n", "            maxLength: 100\n")).isEmpty();
    }

    @Test
    void aResponseValueThatDropsItsTypeIsBreaking() {
        assertThat(changes(TOKEN, "        token: {}\n")).containsExactly("A-7: POST /v1/sessions 201.token changed type from [string] to any");
    }

    @Test
    void aResponseValueThatGainsATypeIsNotBreaking() {
        assertThat(reversed(TOKEN, "        token: {}\n")).isEmpty();
    }

    @Test
    void aRuleForMapValuesWhereThereWasNoneIsBreaking() {
        assertThat(reversed(NOTE_VALUES, "")).containsExactly(
                "A-7: POST /v1/sessions body.notes{} changed type from any to [string]",
                "A-7: POST /v1/sessions body.notes{} tightened maxLength");
    }

    @Test
    void aDroppedRuleForMapValuesIsNotBreakingInARequest() {
        assertThat(changes(NOTE_VALUES, "")).isEmpty();
        assertThat(changes(NOTE_VALUES, "          additionalProperties: true\n")).isEmpty();
    }

    @Test
    void aRequestObjectThatTakesNoOtherPropertiesIsBreaking() {
        assertThat(changes(REQUEST, REQUEST + "      additionalProperties: false\n"))
                .containsExactly("A-7: POST /v1/sessions body no longer takes other properties");
        assertThat(changes(NOTE_VALUES, "          additionalProperties: false\n"))
                .containsExactly("A-7: POST /v1/sessions body.notes no longer takes other properties");
    }

    @Test
    void aRequestObjectThatTakesOtherPropertiesAgainIsNotBreaking() {
        assertThat(reversed(REQUEST, REQUEST + "      additionalProperties: false\n")).isEmpty();
        assertThat(reversed(NOTE_VALUES, "          additionalProperties: false\n")).isEmpty();
    }

    @Test
    void aResponseObjectThatSendsNoOtherPropertiesIsNotBreaking() {
        assertThat(changes(RESPONSE, RESPONSE + "      additionalProperties: false\n")).isEmpty();
    }

    /** Blueprint 6.2: new fields are not breaking, so a response that starts sending other properties is fine. */
    @Test
    void aResponseObjectThatSendsOtherPropertiesAgainIsNotBreaking() {
        assertThat(reversed(RESPONSE, RESPONSE + "      additionalProperties: false\n")).isEmpty();
    }

    @Test
    void aResponseMapWhoseValuesLoseTheirRuleIsBreaking() {
        String map = "        meta:\n          type: object\n";
        String values = "          additionalProperties:\n            type: string\n";

        assertThat(sessionFields(map + values, map)).containsExactly("A-7: POST /v1/sessions 201.meta{} changed type from [string] to any");
        assertThat(sessionFields(map, map + values)).isEmpty();
    }

    @Test
    void aRuleForArrayItemsWhereThereWasNoneIsBreaking() {
        assertThat(reversed(LABEL_ITEMS, NO_LABEL_ITEMS))
                .containsExactly("A-7: POST /v1/sessions body.labels[] changed type from any to [string]");
    }

    @Test
    void aDroppedRuleForArrayItemsIsNotBreakingInARequest() {
        assertThat(changes(LABEL_ITEMS, NO_LABEL_ITEMS)).isEmpty();
        assertThat(changes(LABEL_ITEMS, NO_LABEL_ITEMS + "          items: true\n")).isEmpty();
    }

    @Test
    void aRequestArrayThatTakesNoItemsIsBreaking() {
        assertThat(changes(LABEL_ITEMS, NO_LABEL_ITEMS + "          items: false\n"))
                .containsExactly("A-7: POST /v1/sessions body.labels no longer takes items");
        assertThat(reversed(LABEL_ITEMS, NO_LABEL_ITEMS + "          items: false\n")).isEmpty();
    }

    @Test
    void aResponseArrayWhoseItemsLoseTheirRuleIsBreaking() {
        String array = "        tags:\n          type: array\n";
        String items = "          items:\n            type: string\n";

        assertThat(sessionFields(array + items, array)).containsExactly("A-7: POST /v1/sessions 201.tags[] changed type from [string] to any");
        assertThat(sessionFields(array, array + items)).isEmpty();
    }

    /** A key name is always a string, so saying so changes nothing. */
    @Test
    void aStringTypeOnKeyNamesChangesNothing() {
        assertThat(notes(notesWithKeys("maxLength: 40"), notesWithKeys("type: string\nmaxLength: 40"))).isEmpty();
        assertThat(notes(notesWithKeys("type: string\nmaxLength: 40"), notesWithKeys("maxLength: 40"))).isEmpty();
        assertThat(changes(NOTES, notesWithKeys("type: string\nmaxLength: 40")))
                .containsExactly("A-7: POST /v1/sessions body.notes property names tightened maxLength");
    }

    @Test
    void keyNamesThatTakeNoNameAreBreakingInARequest() {
        assertThat(changes(NOTES, NOTES + "          propertyNames: false\n"))
                .containsExactly("A-7: POST /v1/sessions body.notes no longer takes any property");
        assertThat(reversed(NOTES, NOTES + "          propertyNames: false\n")).isEmpty();
    }

    /** A pair counts as open only while it is compared, so the same change in two places is reported in both. */
    @Test
    void theSameChangeInTwoPlacesIsReportedInBoth() {
        String twice = LABEL_ITEMS + "        aliases:\n          type: array\n          items:\n            type: string\n";
        String tighter = twice.replace("            type: string\n", "            type: string\n            maxLength: 5\n");

        assertThat(ContractDiff.breakingChanges(Contract.parse(legal.replace(LABEL_ITEMS, twice)), Contract.parse(legal.replace(LABEL_ITEMS, tighter))))
                .containsExactly("A-7: POST /v1/sessions body.labels[] tightened maxLength", "A-7: POST /v1/sessions body.aliases[] tightened maxLength");
    }

    /** Comparing no rule with items that are the array itself would never end without a guard. */
    @Test
    void aRuleThatRefersToItselfAgainstNoRuleEnds() {
        String nested = "    Nested:\n      type: array\n      items:\n        $ref: \"#/components/schemas/Nested\"\n";
        String withNested = legal.replace(RESPONSE, nested + RESPONSE);
        String none = withNested.replace(LABEL_ITEMS, NO_LABEL_ITEMS);
        String itself = withNested.replace(LABEL_ITEMS, NO_LABEL_ITEMS + "          items:\n            $ref: \"#/components/schemas/Nested\"\n");

        assertThat(ContractDiff.breakingChanges(Contract.parse(none), Contract.parse(itself)))
                .containsExactly("A-7: POST /v1/sessions body.labels[] changed type from any to [array]");
        assertThat(ContractDiff.breakingChanges(Contract.parse(itself), Contract.parse(none))).isEmpty();
    }
}

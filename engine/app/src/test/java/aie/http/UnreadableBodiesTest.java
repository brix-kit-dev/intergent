package aie.http;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.CreateApiKeyRequest;
import aie.http.api.model.CreateSessionRequest;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The details for bodies that a model cannot read, from the exceptions Jackson really throws. */
class UnreadableBodiesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static String detail(JsonMapper json, Class<?> model, String body) {
        try {
            json.readValue(body, model);
        } catch (JacksonException e) {
            return UnreadableBodies.detail(e);
        }
        throw new AssertionError("The body was read: " + body);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"identity_level":"anonymous"} | identity_level must be one of: unverified, verified
            {"identity_level":{}}          | identity_level must be one of: unverified, verified
            {"external_user_id":{"a":1}}   | external_user_id must be a string
            {"scopes":"a"}                 | scopes must be an array
            {"scopes":["a",{}]}            | scopes[1] must be a string
            {"context":"x"}                | context must be an object
            {"context":{"k":{}}}           | each value in context must be a string
            []                             | The body must be a single JSON object
            {} 5                           | The body must be a single JSON object
            {broken                        | The body is not valid JSON
            """)
    void aSessionBodySaysWhatIsWrong(String body, String detail) {
        assertThat(detail(JSON, CreateSessionRequest.class, body)).isEqualTo(detail);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"expires_at":"tomorrow"} | expires_at must be a date and time such as 2026-01-01T00:00:00Z
            {"name":[1]}              | name must be a string
            """)
    void anApiKeyBodySaysWhatIsWrong(String body, String detail) {
        assertThat(detail(JSON, CreateApiKeyRequest.class, body)).isEqualTo(detail);
    }

    /** Shapes that no request model has yet, so that a new field gets a true detail too. */
    static final class Shapes {
        public int count;
        public boolean flag;
        public double share;
        public Inner inner;
    }

    static final class Inner {
        public String name;
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"count":"many"}        | count must be a whole number
            {"count":99999999999}   | count is out of range
            {"flag":"maybe"}        | flag must be true or false
            {"share":"half"}        | share must be a number
            {"inner":5}             | inner must be an object
            {"inner":{"name":{}}}   | inner.name must be a string
            {"a":1,"b":{"c":[}}     | The body is not valid JSON
            """)
    void otherShapesSayWhatIsWrong(String body, String detail) {
        assertThat(detail(JSON, Shapes.class, body)).isEqualTo(detail);
    }

    /** The caller chose that name, so the detail does not repeat it. */
    @Test
    void aStrictMapperNamesNoUnknownField() {
        JsonMapper strict = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

        assertThat(detail(strict, Shapes.class, "{\"made_up_name\":1}")).isEqualTo("The body has a field that this request does not take");
    }

    @Test
    void noBodyOrAnotherFailureSaysSo() {
        assertThat(UnreadableBodies.detail(null)).isEqualTo("The request needs a JSON body");
        assertThat(UnreadableBodies.detail(new IOException("the connection broke"))).isEqualTo("The body cannot be read");
    }
}

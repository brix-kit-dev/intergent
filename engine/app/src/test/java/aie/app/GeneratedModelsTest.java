package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.Message;
import aie.http.api.model.MessageList;
import aie.http.api.model.Problem;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The JSON the generated models write has to match the contract, also for fields with no value. */
class GeneratedModelsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void anOptionalFieldWithNoValueIsLeftOut() {
        assertThat(JSON.writeValueAsString(new Message().id("msg_1")))
                .contains("\"id\":\"msg_1\"")
                .doesNotContain("usage", "completed_at", "code");
    }

    @Test
    void aRequiredFieldThatCanBeNullIsWritten() {
        assertThat(JSON.writeValueAsString(new MessageList().data(List.of()).nextCursor(null)))
                .isEqualTo("{\"data\":[],\"next_cursor\":null}");
    }

    @Test
    void fieldNamesAreSnakeCase() {
        assertThat(JSON.writeValueAsString(new Message().conversationId("conv_1")))
                .contains("\"conversation_id\":\"conv_1\"");
    }

    @Test
    void aProblemCarriesItsStableCode() {
        Problem problem = new Problem(URI.create("about:blank").toString(), "Not found", 404, "not_found");

        assertThat(JSON.writeValueAsString(problem)).contains("\"code\":\"not_found\"", "\"status\":404");
    }
}

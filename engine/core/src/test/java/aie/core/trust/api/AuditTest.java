package aie.core.trust.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AuditTest {

    /** M3-5-3: an entry added without content comes with none, as a blocked input does. */
    @Test
    void anEntryAddedWithoutContentComesWithNone() {
        List<Audit.Content> got = new ArrayList<>();
        Audit audit = (entry, content) -> got.add(content);

        audit.add(new Audit.Entry(null, "ten_a", "app_a", "eu_a", "conv_a", null, "chat", Audit.Event.INPUT_BLOCKED, null, null,
                null, "rule"));

        assertThat(got).containsExactly(Audit.Content.NONE);
        assertThat(Audit.Content.NONE.question()).isNull();
        assertThat(Audit.Content.NONE.answer()).isNull();
    }

    /** M3-5-3: a content never shows its text, only how long it is, so it cannot reach a log line by mistake. */
    @Test
    void aContentShowsItsLengthsAlone() {
        assertThat(new Audit.Content("the words of the question", null))
                .hasToString("Content[question 25 characters, answer none]");
        assertThat(Audit.Content.NONE).hasToString("Content[question none, answer none]");
    }
}

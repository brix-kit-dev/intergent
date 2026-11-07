package aie.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The cases of W3C Trace Context. */
class TraceparentTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT = "00f067aa0ba902b7";

    @ParameterizedTest
    @ValueSource(strings = {
        "00-" + TRACE + "-" + PARENT + "-01",
        "00-" + TRACE + "-" + PARENT + "-00",
        "01-" + TRACE + "-" + PARENT + "-01",
        "cc-" + TRACE + "-" + PARENT + "-01-more-fields",
        "fe-" + TRACE + "-" + PARENT + "-ff"
    })
    void aValidHeaderGivesItsTraceId(String header) {
        assertThat(Traceparent.traceId(List.of(header))).contains(TRACE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not a traceparent",
        "ff-" + TRACE + "-" + PARENT + "-01",
        "00-00000000000000000000000000000000-" + PARENT + "-01",
        "00-" + TRACE + "-0000000000000000-01",
        "00-" + TRACE + "-" + PARENT + "-01-more-fields",
        "01-" + TRACE + "-" + PARENT + "-01more",
        "00-4BF92F3577B34DA6A3CE929D0E0E4736-" + PARENT + "-01",
        "00-" + TRACE + "-00F067AA0BA902B7-01",
        "00-" + TRACE + "-" + PARENT + "-0G",
        "0G-" + TRACE + "-" + PARENT + "-01",
        "00-" + TRACE + "f-" + PARENT + "-01",
        "00-" + TRACE + "-" + PARENT + "f-01",
        "00-" + TRACE + "-" + PARENT + "-011",
        "00-" + TRACE + "-" + PARENT,
        " 00-" + TRACE + "-" + PARENT + "-01"
    })
    void anInvalidHeaderIsIgnored(String header) {
        assertThat(Traceparent.traceId(List.of(header))).isEmpty();
    }

    @Test
    void noHeaderMeansNoTrace() {
        assertThat(Traceparent.traceId(List.of())).isEmpty();
    }

    @Test
    void twoHeadersAreInvalid() {
        String header = "00-" + TRACE + "-" + PARENT + "-01";

        assertThat(Traceparent.traceId(List.of(header, header))).isEmpty();
    }
}

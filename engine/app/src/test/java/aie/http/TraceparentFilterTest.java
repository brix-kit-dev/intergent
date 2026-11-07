package aie.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** What a real server cannot show: the state left on the thread once a request has ended. */
class TraceparentFilterTest {

    private static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private final TraceparentFilter filter = new TraceparentFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static MockHttpServletRequest traced() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceparentFilter.HEADER, "00-" + TRACE + "-00f067aa0ba902b7-01");
        return request;
    }

    @Test
    void theTraceIsInTheLogsDuringTheRequest() throws Exception {
        AtomicReference<String> logged = new AtomicReference<>();

        filter.doFilter(traced(), response, (request, unused) -> logged.set(MDC.get(TraceparentFilter.LOG_KEY)));

        assertThat(logged).hasValue(TRACE);
    }

    @Test
    void theLogContextEndsWithTheRequest() throws Exception {
        filter.doFilter(traced(), response, (request, unused) -> { });

        assertThat(MDC.get(TraceparentFilter.LOG_KEY)).isNull();
    }

    @Test
    void theLogContextEndsWithAFailedRequestToo() {
        FilterChain failing = (request, unused) -> {
            throw new IllegalStateException("failed");
        };

        assertThatThrownBy(() -> filter.doFilter(traced(), response, failing)).isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(TraceparentFilter.LOG_KEY)).isNull();
    }
}

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
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void theIdOnTheResponseIsTheOneInTheLogs() throws Exception {
        AtomicReference<String> logged = new AtomicReference<>();

        filter.doFilter(new MockHttpServletRequest(), response, (request, unused) -> logged.set(MDC.get(RequestIdFilter.LOG_KEY)));

        assertThat(logged.get()).isNotNull().isEqualTo(response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void theLogContextEndsWithTheRequest() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), response, (request, unused) -> { });

        assertThat(MDC.get(RequestIdFilter.LOG_KEY)).isNull();
    }

    @Test
    void theLogContextEndsWithAFailedRequestToo() {
        FilterChain failing = (request, unused) -> {
            throw new IllegalStateException("failed");
        };

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), response, failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(RequestIdFilter.LOG_KEY)).isNull();
    }
}

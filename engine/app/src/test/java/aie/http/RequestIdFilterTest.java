package aie.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** What a real server cannot show: the state left on the thread once a request has ended. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final FilterChain failing = (unused, answer) -> {
        throw new IllegalStateException("failed");
    };

    @Test
    void theIdOnTheResponseIsTheOneInTheLogs() throws Exception {
        AtomicReference<String> logged = new AtomicReference<>();

        filter.doFilter(request, response, (unused, answer) -> logged.set(MDC.get(RequestIdFilter.LOG_KEY)));

        assertThat(logged.get()).isNotNull().isEqualTo(response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void theLogContextEndsWithTheRequest() throws Exception {
        filter.doFilter(request, response, (unused, answer) -> { });

        assertThat(MDC.get(RequestIdFilter.LOG_KEY)).isNull();
    }

    @Test
    void theLogContextEndsWithAFailedRequestToo() throws Exception {
        filter.doFilter(request, response, failing);

        assertThat(MDC.get(RequestIdFilter.LOG_KEY)).isNull();
    }

    /** Code point 31: the error page gets the failure from here, so Tomcat never logs it without the ID. */
    @Test
    void aFailureGoesToTheErrorPageWithItsCause() throws Exception {
        filter.doFilter(request, response, failing);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION)).isInstanceOf(IllegalStateException.class)
                .hasFieldOrPropertyWithValue("message", "failed");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotNull();
    }

    /** An answer that has started cannot become a problem any more, so the failure goes on to Tomcat. */
    @Test
    void aFailureAfterTheAnswerStartedGoesOn() {
        FilterChain late = (unused, answer) -> {
            answer.flushBuffer();
            throw new IllegalStateException("late");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, late)).isInstanceOf(IllegalStateException.class).hasMessage("late");
        assertThat(MDC.get(RequestIdFilter.LOG_KEY)).isNull();
    }

    /** An I/O failure is mostly a client that went away, which Tomcat logs at debug level only. An Error stays its own. */
    @Test
    void anIoFailureOrAnErrorGoesOn() {
        FilterChain gone = (unused, answer) -> {
            throw new IOException("gone");
        };
        FilterChain broken = (unused, answer) -> {
            throw new AssertionError("broken");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, gone)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), broken))
                .isInstanceOf(AssertionError.class);
        assertThat(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION)).isNull();
    }
}

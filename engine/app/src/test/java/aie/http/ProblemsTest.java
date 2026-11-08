package aie.http;

import static org.assertj.core.api.Assertions.assertThat;

import aie.http.api.model.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProblemsTest {

    @ParameterizedTest
    @CsvSource({
        "400, invalid_request, Bad Request",
        "401, unauthorized, Unauthorized",
        "403, forbidden, Forbidden",
        "404, not_found, Not Found",
        "405, invalid_request, Method Not Allowed",
        "406, invalid_request, Not Acceptable",
        "413, invalid_request, Content Too Large",
        "415, invalid_request, Unsupported Media Type",
        "500, internal_error, Internal Server Error",
        "503, internal_error, Service Unavailable"
    })
    void aStatusBecomesItsCodeAndTitle(int status, String code, String title) {
        Problem problem = Problems.of(status, null);

        assertThat(problem.getCode()).isEqualTo(code);
        assertThat(problem.getTitle()).isEqualTo(title);
        assertThat(problem.getStatus()).isEqualTo(status);
        assertThat(problem.getType()).isEqualTo("about:blank");
    }

    @Test
    void aStatusHttpDoesNotNameStillGetsATitle() {
        assertThat(Problems.of(599, null).getTitle()).isEqualTo("Error");
    }
}

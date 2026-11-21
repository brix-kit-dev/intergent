package aie.core.metering.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MeteringTest {

    private static Metering.Use use(String tenant, String application, String message, String task, String model, long in, long out,
            BigDecimal cost) {
        return new Metering.Use("req_1", tenant, application, message, task, model, in, out, cost);
    }

    /** M3-6-1: a use names who pays and what was called; a call no request made has no request ID. */
    @Test
    void aUseNamesWhoPaysAndWhatWasCalled() {
        Metering.Use use = new Metering.Use(null, "ten_a", "app_a", "msg_a", "chat", "m", 5, 2, new BigDecimal("0.000045"));

        assertThat(use.requestId()).isNull();
        assertThat(use.cost()).isEqualByComparingTo("0.000045");
        for (Runnable missing : List.<Runnable>of(() -> use(null, "app_a", "msg_a", "chat", "m", 0, 0, BigDecimal.ZERO),
                () -> use("ten_a", null, "msg_a", "chat", "m", 0, 0, BigDecimal.ZERO),
                () -> use("ten_a", "app_a", null, "chat", "m", 0, 0, BigDecimal.ZERO),
                () -> use("ten_a", "app_a", "msg_a", null, "m", 0, 0, BigDecimal.ZERO),
                () -> use("ten_a", "app_a", "msg_a", "chat", null, 0, 0, BigDecimal.ZERO),
                () -> use("ten_a", "app_a", "msg_a", "chat", "m", 0, 0, null))) {
            assertThatThrownBy(missing::run).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A use names its tenant, application, message, task and model, and its cost");
        }
    }

    /** M3-6-1: a use counts no fewer than 0 tokens and costs no less than nothing. */
    @Test
    void aUseCountsAndCostsZeroOrMore() {
        assertThatThrownBy(() -> use("ten_a", "app_a", "msg_a", "chat", "m", -1, 0, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A use counts 0 tokens or more and costs 0 or more, not -1 and 0 tokens for 0");
        assertThatThrownBy(() -> use("ten_a", "app_a", "msg_a", "chat", "m", 0, -1, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> use("ten_a", "app_a", "msg_a", "chat", "m", 0, 0, new BigDecimal("-0.000001")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

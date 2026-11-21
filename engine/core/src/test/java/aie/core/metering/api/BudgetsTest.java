package aie.core.metering.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class BudgetsTest {

    private static Budgets.Standing standing(String limit, String spent) {
        return new Budgets.Standing(YearMonth.of(2026, 11), new BigDecimal(limit), false, new BigDecimal(spent));
    }

    /**
     * M3-6-2: a call may start only while the tenant has spent less than its limit, by however little. A tenant that has
     * spent its limit exactly, however the amounts are written, has spent it, and a limit of 0 allows no call at all.
     */
    @Test
    void aCallMayStartOnlyWhileTheTenantHasSpentLessThanItsLimit() {
        assertThat(standing("100", "0").allowsACall()).isTrue();
        assertThat(standing("100", "99.999999999").allowsACall()).isTrue();
        assertThat(standing("0.000001", "0").allowsACall()).isTrue();
        assertThat(standing("100", "100").allowsACall()).isFalse();
        assertThat(standing("100", "100.000000").allowsACall()).isFalse();
        assertThat(standing("12.50", "12.500000001").allowsACall()).isFalse();
        assertThat(standing("0", "0").allowsACall()).isFalse();
    }

    /** M3-6-2: a standing has its month, its limit and what was spent. */
    @Test
    void aStandingHasItsMonthItsLimitAndWhatWasSpent() {
        for (Runnable missing : List.<Runnable>of(() -> new Budgets.Standing(null, BigDecimal.ONE, false, BigDecimal.ZERO),
                () -> new Budgets.Standing(YearMonth.of(2026, 11), null, false, BigDecimal.ZERO),
                () -> new Budgets.Standing(YearMonth.of(2026, 11), BigDecimal.ONE, false, null))) {
            assertThatThrownBy(missing::run).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A standing has its month, its limit and what was spent");
        }
    }
}

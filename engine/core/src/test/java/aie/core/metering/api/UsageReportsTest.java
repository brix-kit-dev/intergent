package aie.core.metering.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class UsageReportsTest {

    private static final Instant FROM = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-01T00:00:00Z");

    /** M3-6-4: a report has its window, its cost and its models, and keeps its own list of them. */
    @Test
    void aReportHasItsWindowItsCostAndItsModels() {
        List<UsageReports.ModelUse> models = new ArrayList<>(List.of(new UsageReports.ModelUse("m", 1, 2, BigDecimal.ONE)));
        UsageReports.Report report = new UsageReports.Report(FROM, TO, 1, 2, BigDecimal.ONE, models);
        models.clear();

        assertThat(report.byModel()).hasSize(1);
        for (Runnable missing : List.<Runnable>of(() -> new UsageReports.Report(null, TO, 0, 0, BigDecimal.ZERO, List.of()),
                () -> new UsageReports.Report(FROM, null, 0, 0, BigDecimal.ZERO, List.of()),
                () -> new UsageReports.Report(FROM, TO, 0, 0, null, List.of()),
                () -> new UsageReports.Report(FROM, TO, 0, 0, BigDecimal.ZERO, null))) {
            assertThatThrownBy(missing::run).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("A report has its window, its cost and its models");
        }
    }

    /** M3-6-4: a model's use names the model and its cost. */
    @Test
    void aModelsUseNamesTheModelAndItsCost() {
        assertThatThrownBy(() -> new UsageReports.ModelUse(null, 0, 0, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A model's use names the model and its cost");
        assertThatThrownBy(() -> new UsageReports.ModelUse("m", 0, 0, null)).isInstanceOf(IllegalArgumentException.class);
    }
}

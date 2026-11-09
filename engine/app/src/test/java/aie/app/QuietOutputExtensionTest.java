package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;

/** QuietOutput as an extension: each test gets what it printed, and only that. */
@ExtendWith(QuietOutput.class)
class QuietOutputExtensionTest {

    private static final String NL = System.lineSeparator();

    @Test
    void aTestReadsWhatItPrinted(CapturedOutput output) {
        System.out.println("printed");
        System.err.println("complained");

        assertThat(output.getOut()).isEqualTo("printed" + NL);
        assertThat(output.getErr()).isEqualTo("complained" + NL);
        assertThat(output.getAll()).isEqualTo("printed" + NL + "complained" + NL);
    }

    @Test
    void theNextTestStartsEmpty(CapturedOutput output) {
        System.out.print("again");

        assertThat(output.getOut()).isEqualTo("again");
    }
}

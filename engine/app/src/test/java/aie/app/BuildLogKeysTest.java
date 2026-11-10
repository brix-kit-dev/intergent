package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.core.tenancy.api.ApiKeys;
import aie.core.tenancy.api.SiteKeys;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Code point 28: verify.sh fails the build when the build printed a key. These tests hold the shape it looks for to the
 * keys the engine really issues. They never print a key: a failure names only the kind of key, its environment and cell.
 */
class BuildLogKeysTest {

    private static final List<String> ENVIRONMENTS = List.of("test", "live");
    /** The shortest and the longest cell code, and two in between. */
    private static final List<String> CELLS = List.of("a", "dev", "jp1", "cell0001");

    private static Pattern shape;

    @BeforeAll
    static void readTheShape() throws IOException, URISyntaxException {
        Path testClasses = Path.of(BuildLogKeysTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String script = Files.readString(testClasses.getParent().getParent().getParent().resolve("verify.sh"));
        Matcher line = Pattern.compile("(?m)^key_shape='([^']+)'$").matcher(script);
        assertThat(line.find()).as("verify.sh sets key_shape").isTrue();
        shape = Pattern.compile(line.group(1));
    }

    private static boolean found(String printed) {
        return shape.matcher(printed).find();
    }

    @Test
    void itFindsEveryApiKey() {
        for (String environment : ENVIRONMENTS) {
            for (String cell : CELLS) {
                assertThat(found("created key_01 " + ApiKeys.issue(environment, cell) + ".")).as("API key, %s, %s", environment, cell).isTrue();
            }
        }
    }

    @Test
    void itFindsEverySiteKey() {
        for (String environment : ENVIRONMENTS) {
            for (String cell : CELLS) {
                assertThat(found("created site_01 " + SiteKeys.issue(environment, cell) + ".")).as("site key, %s, %s", environment, cell).isTrue();
            }
        }
    }

    /** After a failed test, QuietOutput prints what the test printed with every key masked. That must not fail the build. */
    @Test
    void itLetsAMaskedKeyThrough() {
        assertThat(found(QuietOutput.masked("created key_01 " + ApiKeys.issue("live", "cell0001")))).isFalse();
        assertThat(found(QuietOutput.masked("created site_01 " + SiteKeys.issue("test", "a")))).isFalse();
    }
}

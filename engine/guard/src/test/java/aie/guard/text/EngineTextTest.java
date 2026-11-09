package aie.guard.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the text rules on the real engine. The files are found on disk, so a new module cannot be left out. */
class EngineTextTest {

    private static Path engine;

    @BeforeAll
    static void findTheEngine() throws URISyntaxException {
        Path testClasses = Path.of(EngineTextTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        engine = testClasses.getParent().getParent().getParent();
    }

    @Test
    void theEngineKeepsTheTextRules() throws IOException {
        assertThat(TextRules.violations(engine)).isEmpty();
    }

    /** A scan that reads nothing would pass for the wrong reason, so it must reach every kind of source it covers. */
    @Test
    void theScanReachesTheEnginesSources() throws IOException {
        List<String> sources = TextRules.sources(engine).stream().map(file -> engine.relativize(file).toString()).toList();

        assertThat(sources).anyMatch(path -> path.startsWith("core/src/main/java/aie/core/tenancy/"))
                .anyMatch(path -> path.startsWith("http/src/main/java/aie/http/"))
                .anyMatch(path -> path.startsWith("app/src/main/java/aie/app/"))
                .anyMatch(path -> path.startsWith("adapters/store-postgres/src/main/java/"))
                .anyMatch(path -> path.startsWith("core/src/main/resources/db/migration/tenancy/") && path.endsWith(".sql"))
                .noneMatch(path -> path.startsWith("guard/"));
    }
}

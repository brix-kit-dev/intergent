package aie.guard.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseAgeTest {

    private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
    private static final String PATH = "org/example/lib/1.0/lib-1.0.pom";
    private static final String COORDINATES = "org.example:lib:1.0";
    private static final Map<String, String> VERSIONS = Map.of(COORDINATES, PATH);
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    @TempDir
    Path directory;

    private static List<String> violations(Instant published, Set<String> approved) {
        return ReleaseAge.violations(VERSIONS, Map.of(PATH, published), approved, NOW);
    }

    @Test
    void anOldVersionPasses() {
        assertThat(violations(NOW.minus(Duration.ofDays(30)), Set.of())).isEmpty();
    }

    @Test
    void exactlySevenDaysPasses() {
        assertThat(violations(NOW.minus(SEVEN_DAYS), Set.of())).isEmpty();
    }

    @Test
    void oneSecondUnderSevenDaysFails() {
        assertThat(violations(NOW.minus(SEVEN_DAYS).plusSeconds(1), Set.of()))
                .singleElement().asString().contains("S-8", COORDINATES);
    }

    @Test
    void aVersionWithNoDateFails() {
        assertThat(ReleaseAge.violations(VERSIONS, Map.of(), Set.of(), NOW))
                .singleElement().asString().contains("S-8", "no release date", COORDINATES);
    }

    @Test
    void anApprovedVersionPasses() {
        assertThat(violations(NOW, Set.of(COORDINATES))).isEmpty();
    }

    @Test
    void anApprovalCoversOnlyItsExactVersion() {
        assertThat(violations(NOW, Set.of("org.example:lib:1.1"))).hasSize(1);
    }

    @Test
    void oneFileStandsForEachVersion() {
        assertThat(ReleaseAge.versions(List.of(
                        "org/example/lib/1.0/lib-1.0.jar",
                        "org/example/lib/1.0/lib-1.0.pom",
                        "org/example/lib/2.0/lib-2.0.jar",
                        "commons-io/commons-io/2.0/commons-io-2.0.pom")))
                .containsOnlyKeys("org.example:lib:1.0", "org.example:lib:2.0", "commons-io:commons-io:2.0")
                .containsEntry("org.example:lib:1.0", "org/example/lib/1.0/lib-1.0.jar");
    }

    @Test
    void anExceptionNamesWhoApprovedItAndWhy() throws IOException {
        Path file = directory.resolve("exceptions.txt");
        Files.writeString(file, """
                # comment

                org.example:lib:1.0  2026-11-06  owner  fixes CVE-2026-0001
                """);

        assertThat(ReleaseAge.readExceptions(file)).containsExactly(COORDINATES);
    }

    @Test
    void anExceptionWithoutAReasonIsRefused() throws IOException {
        Path file = directory.resolve("exceptions.txt");
        Files.writeString(file, "org.example:lib:1.0  2026-11-06  owner\n");

        assertThatThrownBy(() -> ReleaseAge.readExceptions(file)).hasMessageContaining("S-8");
    }

    @Test
    void anExceptionWithABadDateIsRefused() throws IOException {
        Path file = directory.resolve("exceptions.txt");
        Files.writeString(file, "org.example:lib:1.0  2026-13-40  owner  reason\n");

        assertThatThrownBy(() -> ReleaseAge.readExceptions(file)).isInstanceOf(RuntimeException.class);
    }
}

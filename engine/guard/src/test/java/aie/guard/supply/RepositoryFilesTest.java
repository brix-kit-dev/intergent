package aie.guard.supply;

import static org.assertj.core.api.Assertions.assertThat;

import aie.guard.supply.DependencyLock.Entry;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the rules on the real files, and keeps the rules themselves from being switched off. */
class RepositoryFilesTest {

    private static Path engine;
    private static Path repository;

    @BeforeAll
    static void findTheRepository() throws URISyntaxException {
        Path testClasses = Path.of(RepositoryFilesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        engine = testClasses.getParent().getParent().getParent();
        repository = engine.getParent();
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(repository.resolve(".github/workflows"))) {
            return files.toList();
        }
    }

    private static List<Path> poms() throws IOException {
        try (Stream<Path> files = Files.walk(engine)) {
            return files.filter(file -> file.getFileName().toString().equals("pom.xml"))
                    .filter(file -> !engine.relativize(file).toString().contains("target/"))
                    .toList();
        }
    }

    @Test
    void theRepositoryFollowsThePnpmRules() throws IOException {
        assertThat(PnpmPolicy.violations(repository)).isEmpty();
    }

    @Test
    void everyWorkflowFollowsTheRules() throws IOException {
        assertThat(workflows()).isNotEmpty();
        for (Path workflow : workflows()) {
            assertThat(WorkflowPolicy.violations(workflow.getFileName().toString(), Files.readString(workflow), LocalDate.now()))
                    .isEmpty();
        }
    }

    @Test
    void everyComposeFileFollowsTheRules() throws IOException {
        List<Path> composeFiles;
        try (Stream<Path> files = Files.walk(repository.resolve("deploy"))) {
            composeFiles = files.filter(file -> file.getFileName().toString().matches("compose.*\\.ya?ml")).toList();
        }
        assertThat(composeFiles).isNotEmpty();
        for (Path file : composeFiles) {
            assertThat(WorkflowPolicy.composeViolations(file.getFileName().toString(), Files.readString(file), LocalDate.now())).isEmpty();
        }
    }

    @Test
    void ciRunsTheWholeCheck() throws IOException {
        assertThat(Files.readString(repository.resolve(".github/workflows/ci.yml")))
                .contains("run: engine/verify.sh\n");
        assertThat(Files.readString(engine.resolve("verify.sh")))
                .contains("mvn -B -ntp -Dmaven.repo.local=\"$repository\" clean verify\n")
                .contains("\"${check[@]}\" check . \"$repository\"\n")
                .contains("set -euo pipefail\n");
    }

    @Test
    void thePinRulesStayInTheBuild() throws IOException {
        assertThat(Files.readString(engine.resolve("pom.xml")))
                .contains("<id>s8-pinned-versions</id>")
                .contains("<requirePluginVersions>")
                .contains("<banDynamicVersions>")
                .contains("<requireNoRepositories>");
    }

    /** A flag in a POM or in .mvn would switch a check off everywhere, with no change to CI. */
    @Test
    void nothingSwitchesACheckOff() throws IOException {
        assertThat(engine.resolve(".mvn")).doesNotExist();
        assertThat(repository.resolve(".mvn")).doesNotExist();
        assertThat(poms()).hasSizeGreaterThan(1);
        for (Path pom : poms()) {
            assertThat(Files.readString(pom)).as(pom.toString())
                    .doesNotContainPattern("<skip>|<fail>false|enforcer\\.skip|skipTests|maven\\.test\\.|testFailureIgnore");
        }
    }

    @Test
    void theLockIsWellFormed() throws IOException {
        List<Entry> entries = DependencyLock.read(engine.resolve("dependency-lock.txt"));

        assertThat(entries).isNotEmpty();
        assertThat(entries).extracting(Entry::path).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void theExceptionsFileIsWellFormed() throws IOException {
        ReleaseAge.readExceptions(engine.resolve("release-age-exceptions.txt"));
    }

    @Test
    void thePolicyNeverAllowsTheGplFamily() throws IOException {
        assertThat(EngineLicenses.readPolicy(engine.resolve("license-policy.txt")))
                .isNotEmpty()
                .noneMatch(name -> name.matches("(?i).*(gpl|gnu|general public).*"));
    }
}

package aie.guard.supply;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkflowPolicyTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-06");
    private static final String COMMIT = "3d3c42e5aac5ba805825da76410c181273ba90b1";
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String HASH = "b".repeat(64);
    private static final String LEGAL = """
            name: CI
            on:
              push:
            permissions:
              contents: read
            jobs:
              verify:
                runs-on: ubuntu-24.04
                container:
                  image: eclipse-temurin:21@%s # JDK 21, released 2026-10-26
                env:
                  MAVEN_SHA256: %s # Maven 3.8.7, released 2022-12-24
                steps:
                  - uses: actions/checkout@%s # v7.0.1, released 2026-07-17
                    with:
                      persist-credentials: false
                  - run: |
                      curl -fsSL -o /tmp/m.tgz https://example.org/m.tgz
                      echo "$MAVEN_SHA256  /tmp/m.tgz" | sha256sum -c -
                  - run: engine/verify.sh
            """.formatted(DIGEST, HASH, COMMIT);

    private static List<String> violations(String text) {
        return WorkflowPolicy.violations("ci.yml", text, TODAY);
    }

    private static String replaced(String from, String to) {
        assertThat(LEGAL).contains(from);
        return LEGAL.replace(from, to);
    }

    @Test
    void theLegalWorkflowPasses() {
        assertThat(violations(LEGAL)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"v7", "v7.0.1", "main", "3d3c42e"})
    void anActionNotPinnedToACommitFails(String reference) {
        assertThat(violations(replaced("@" + COMMIT, "@" + reference)))
                .singleElement().asString().contains("S-8", "ci.yml:14", "not pinned to a commit");
    }

    @Test
    void anImageWithoutADigestFails() {
        assertThat(violations(replaced("eclipse-temurin:21@" + DIGEST, "eclipse-temurin:21")))
                .singleElement().asString().contains("S-8", "ci.yml:10", "not pinned to a digest");
    }

    @Test
    void aBadDownloadHashFails() {
        assertThat(violations(replaced(HASH, "abc")))
                .singleElement().asString().contains("S-8", "ci.yml:12", "not pinned to a sha256");
    }

    @Test
    void aPinWithoutAReleaseDateFails() {
        assertThat(violations(replaced(" # v7.0.1, released 2026-07-17", "")))
                .singleElement().asString().contains("S-8", "ci.yml:14", "released YYYY-MM-DD");
    }

    @ParameterizedTest
    @CsvSource({"2026-10-31,true", "2026-10-30,false"})
    void aToolHasToWaitSevenDays(String released, boolean tooNew) {
        List<String> problems = violations(replaced("released 2026-07-17", "released " + released));

        assertThat(!problems.isEmpty()).isEqualTo(tooNew);
        if (tooNew) {
            assertThat(problems).singleElement().asString().contains("S-8", "less than 7 days");
        }
    }

    @Test
    void anImpossibleReleaseDateFails() {
        assertThat(violations(replaced("released 2026-07-17", "released 2026-13-40")))
                .singleElement().asString().contains("S-8", "bad release date");
    }

    @Test
    void aLocalActionNeedsNoPin() {
        assertThat(violations(LEGAL + "      - uses: ./.github/actions/local\n")).isEmpty();
    }

    @Test
    void aCommentedOutLineIsIgnored() {
        assertThat(violations(LEGAL + "      # - run: curl https://example.org/install | sh, with secrets.TOKEN\n")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "  pull_request_target:",
        "        with: {token: '${{ secrets.TOKEN }}'}",
        "  id-token: write",
        "permissions: write-all",
        "        continue-on-error: true",
        "      - run: engine/verify.sh || true",
        "    runs-on: ubuntu-latest",
        "      - run: mvn verify -DskipTests",
        "      - run: mvn verify -Dmaven.test.skip=true",
        "      - run: mvn verify -Dmaven.test.failure.ignore=true",
        "      - run: mvn verify -Denforcer.skip=true",
        "      - run: mvn verify --fail-never",
        "      - run: mvn verify -fn",
        "      - run: curl https://example.org/install | sh",
        "      - run: curl https://example.org/install | bash",
        "          cache: maven",
        "    container: eclipse-temurin:21",
        "      - run: apt-get install -y jq",
        "      - run: apt-get -y install jq",
        "      - run: npm install -g pnpm",
        "      - run: npm i -g pnpm",
        "      - run: pip install pyyaml"
    })
    void aLineThatWeakensTheCheckFails(String line) {
        assertThat(violations(LEGAL + line + "\n")).isNotEmpty().allSatisfy(problem -> assertThat(problem).contains("S-8"));
    }

    @Test
    void aCacheActionFails() {
        assertThat(violations(LEGAL + "      - uses: actions/cache@" + COMMIT + " # v5, released 2026-01-01\n"))
                .singleElement().asString().contains("S-8", "cache");
    }

    @Test
    void permissionsMustBeReadOnly() {
        assertThat(violations(replaced("permissions:\n  contents: read\n", "")))
                .singleElement().asString().contains("S-8", "contents: read");
    }

    @Test
    void checkoutMustDropItsCredentials() {
        assertThat(violations(replaced("persist-credentials: false", "fetch-depth: 1")))
                .singleElement().asString().contains("S-8", "persist-credentials");
    }

    @Test
    void aDownloadMustBeChecked() {
        assertThat(violations(replaced("sha256sum -c -", "cat")))
                .singleElement().asString().contains("S-8", "sha256sum -c");
    }
}

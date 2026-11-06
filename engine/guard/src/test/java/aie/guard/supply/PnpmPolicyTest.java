package aie.guard.supply;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PnpmPolicyTest {

    private static final String PINNED = "{\"packageManager\": \"pnpm@12.16.1+sha512." + "ab".repeat(64) + "\"}";
    private static final String SETTINGS = "packages:\n  - widget\nminimumReleaseAge: 10080\n";

    @TempDir
    Path repository;

    private void write(String file, String text) throws IOException {
        Files.createDirectories(repository.resolve(file).getParent());
        Files.writeString(repository.resolve(file), text);
    }

    private void legalWorkspace() throws IOException {
        write("web/package.json", PINNED);
        write("web/pnpm-workspace.yaml", SETTINGS);
        write("web/pnpm-lock.yaml", "lockfileVersion: '9.0'\n");
        write("web/widget/package.json", "{\"name\": \"widget\"}");
    }

    private List<String> violations() throws IOException {
        return PnpmPolicy.violations(repository);
    }

    @Test
    void aRepositoryWithoutJavaScriptHasNothingToCheck() throws IOException {
        write("engine/pom.xml", "<project/>");

        assertThat(violations()).isEmpty();
    }

    @Test
    void aLegalWorkspacePasses() throws IOException {
        legalWorkspace();

        assertThat(violations()).isEmpty();
    }

    @Test
    void aLongerWaitPasses() throws IOException {
        legalWorkspace();
        write("web/pnpm-workspace.yaml", "minimumReleaseAge: 20160 # two weeks\n");

        assertThat(violations()).isEmpty();
    }

    @Test
    void aBuildDeniedByAllowBuildsPasses() throws IOException {
        legalWorkspace();
        write("web/pnpm-workspace.yaml", SETTINGS + "allowBuilds:\n  core-js: false\n");

        assertThat(violations()).isEmpty();
    }

    @Test
    void aPackageOutsideAnyWorkspaceFails() throws IOException {
        write("sdk/typescript/package.json", PINNED);

        assertThat(violations()).singleElement().asString().contains("S-8", "sdk/typescript/package.json");
    }

    @Test
    void packagesInsideNodeModulesAreNotOurs() throws IOException {
        write("web/node_modules/foo/package.json", "{}");
        write("engine/app/target/classes/package.json", "{}");

        assertThat(violations()).isEmpty();
    }

    @Test
    void aMissingLockFileFails() throws IOException {
        legalWorkspace();
        Files.delete(repository.resolve("web/pnpm-lock.yaml"));

        assertThat(violations()).singleElement().asString().contains("S-8", "pnpm-lock.yaml", "'web'");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        "{\"packageManager\": \"pnpm@latest\"}",
        "{\"packageManager\": \"pnpm@12.16.1\"}",
        "{\"packageManager\": \"pnpm@12\"}",
        "{\"packageManager\": \"pnpm@latest+sha512.abcd\"}",
        "{\"packageManager\": \"pnpm@12+sha512.abcd\"}",
        "{\"packageManager\": \"npm@11.0.0+sha512.abcd\"}"
    })
    void anUnpinnedPackageManagerFails(String manifest) throws IOException {
        legalWorkspace();
        write("web/package.json", manifest);

        assertThat(violations()).singleElement().asString().contains("S-8", "packageManager");
    }

    @Test
    void aWorkspaceWithoutItsOwnManifestFails() throws IOException {
        legalWorkspace();
        Files.delete(repository.resolve("web/package.json"));

        assertThat(violations()).singleElement().asString().contains("S-8", "packageManager");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "packages:\n  - widget\n",
        "minimumReleaseAge: 10079\n",
        "minimumReleaseAge: 0\n",
        "# minimumReleaseAge: 10080\n",
        "nested:\n  minimumReleaseAge: 10080\n"
    })
    void aWaitUnderSevenDaysFails(String settings) throws IOException {
        legalWorkspace();
        write("web/pnpm-workspace.yaml", settings);

        assertThat(violations()).singleElement().asString().contains("S-8", "minimumReleaseAge");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "dangerouslyAllowAllBuilds: true\n",
        "onlyBuiltDependencies:\n  - esbuild\n",
        "permissions:\n  esbuild:\n    build: true\n",
        "minimumReleaseAgeExclude:\n  - foo\n",
        "allowBuilds: {esbuild: true}\n",
        "allowBuilds:\n  esbuild: true\n",
        "allowBuilds:\n  core-js: false\n  esbuild: true\n"
    })
    void aSettingThatOpensTheDoorFails(String setting) throws IOException {
        legalWorkspace();
        write("web/pnpm-workspace.yaml", SETTINGS + setting);

        assertThat(violations()).singleElement().asString().contains("S-8");
    }

    @Test
    void everyWorkspaceIsChecked() throws IOException {
        legalWorkspace();
        write("sdk/typescript/package.json", PINNED);
        write("sdk/typescript/pnpm-workspace.yaml", "minimumReleaseAge: 10080\n");

        assertThat(violations()).singleElement().asString().contains("S-8", "pnpm-lock.yaml", "sdk/typescript");
    }
}

package aie.guard.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.guard.supply.DependencyLock.Entry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineLicensesTest {

    private static final Set<String> ALLOWED = Set.of("Apache-2.0", "EPL-2.0");

    @TempDir
    Path repository;

    @TempDir
    Path output;

    private final List<Entry> lock = new ArrayList<>();
    private final Map<String, byte[]> libraries = new LinkedHashMap<>();

    private static byte[] jar(String entryName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write(entryName.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String licenses(String... names) {
        return List.of(names).stream()
                .map(name -> "<license><name>" + name + "</name></license>")
                .collect(Collectors.joining("", "<licenses>", "</licenses>"));
    }

    private static String parent(String artifact) {
        return "<parent><groupId>org.example</groupId><artifactId>" + artifact + "</artifactId><version>1.0</version></parent>";
    }

    private void pom(String artifact, String body) throws IOException {
        Path file = repository.resolve("org/example/" + artifact + "/1.0/" + artifact + "-1.0.pom");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" + body + "</project>");
    }

    private void library(String artifact, String body) throws IOException {
        pom(artifact, body);
        libraryWithoutPom(artifact, "");
    }

    private void libraryWithoutPom(String artifact, String classifier) throws IOException {
        String file = artifact + "-1.0" + classifier + ".jar";
        byte[] content = jar(artifact + classifier + ".class");
        lock.add(new Entry(DependencyLock.sha256(content), "org/example/" + artifact + "/1.0/" + file));
        libraries.put(file, content);
    }

    private List<String> violations() throws IOException {
        Path engineJar = output.resolve("engine.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(engineJar))) {
            zip.putNextEntry(new ZipEntry("BOOT-INF/classes/aie/app/Main.class"));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> library : libraries.entrySet()) {
                zip.putNextEntry(new ZipEntry("BOOT-INF/lib/" + library.getKey()));
                zip.write(library.getValue());
                zip.closeEntry();
            }
        }
        return EngineLicenses.violations(engineJar, lock, repository, ALLOWED);
    }

    @Test
    void anAllowedLicensePasses() throws IOException {
        library("good", licenses("Apache-2.0"));

        assertThat(violations()).isEmpty();
    }

    @Test
    void gplFails() throws IOException {
        library("bad", licenses("GNU General Public License, version 2"));

        assertThat(violations()).singleElement().asString()
                .contains("S-9", "bad-1.0.jar", "GNU General Public License, version 2");
    }

    @Test
    void agplFails() throws IOException {
        library("bad", licenses("GNU Affero General Public License v3"));

        assertThat(violations()).singleElement().asString().contains("S-9", "bad-1.0.jar");
    }

    @Test
    void lgplAloneFails() throws IOException {
        library("bad", licenses("LGPL-2.1-only"));

        assertThat(violations()).singleElement().asString().contains("S-9", "bad-1.0.jar");
    }

    @Test
    void aLicenseOutsideThePolicyFails() throws IOException {
        library("odd", licenses("Some Custom License"));

        assertThat(violations()).singleElement().asString().contains("S-9", "odd-1.0.jar", "Some Custom License");
    }

    @Test
    void aChoiceWithOneAllowedLicensePasses() throws IOException {
        library("dual", licenses("LGPL-2.1-only", "EPL-2.0"));

        assertThat(violations()).isEmpty();
    }

    @Test
    void aJarWithNoLicenseAnywhereFails() throws IOException {
        library("none", "");

        assertThat(violations()).singleElement().asString().contains("S-9", "no license", "none-1.0.jar");
    }

    @Test
    void theLicenseCanComeFromTheParent() throws IOException {
        pom("base", licenses("Apache-2.0"));
        library("child", parent("base"));

        assertThat(violations()).isEmpty();
    }

    @Test
    void theLicenseCanComeFromAGrandparent() throws IOException {
        pom("root", licenses("GPL-3.0"));
        pom("base", parent("root"));
        library("child", parent("base"));

        assertThat(violations()).singleElement().asString().contains("S-9", "GPL-3.0");
    }

    @Test
    void theNearestLicenseWins() throws IOException {
        pom("base", licenses("Apache-2.0"));
        library("child", parent("base") + licenses("GPL-3.0"));

        assertThat(violations()).singleElement().asString().contains("S-9", "GPL-3.0");
    }

    @Test
    void aParentMissingFromTheRepositoryMeansNoLicense() throws IOException {
        library("child", parent("gone"));

        assertThat(violations()).singleElement().asString().contains("S-9", "no license");
    }

    @Test
    void aLicenseInsideAProfileDoesNotCount() throws IOException {
        library("child", "<profiles><profile>" + licenses("Apache-2.0") + "</profile></profiles>");

        assertThat(violations()).singleElement().asString().contains("S-9", "no license");
    }

    @Test
    void aJarWithAClassifierReadsTheVersionPom() throws IOException {
        pom("native", licenses("GPL-3.0"));
        libraryWithoutPom("native", "-linux");

        assertThat(violations()).singleElement().asString().contains("S-9", "native-1.0-linux.jar", "GPL-3.0");
    }

    @Test
    void aJarThatIsNotInTheLockFails() throws IOException {
        libraries.put("unknown-1.0.jar", jar("unknown.class"));

        assertThat(violations()).singleElement().asString().contains("S-9", "not in the lock", "unknown-1.0.jar");
    }

    @Test
    void ourOwnModulesAreSkipped() throws IOException {
        libraries.put("engine-http-0.1.0.jar", jar("META-INF/maven/aie/engine-http/pom.properties"));

        assertThat(violations()).isEmpty();
    }

    @Test
    void anEngineJarWithNoLibrariesFails() throws IOException {
        assertThat(violations()).singleElement().asString().contains("S-9", "no jars");
    }

    @Test
    void aPolicyLineThatIsNotAnAllowIsRefused() throws IOException {
        Path policy = output.resolve("policy.txt");
        Files.writeString(policy, "Apache-2.0\n");

        assertThatThrownBy(() -> EngineLicenses.readPolicy(policy)).hasMessageContaining("S-9");
    }

    @Test
    void thePolicyKeepsTheWholeName() throws IOException {
        Path policy = output.resolve("policy.txt");
        Files.writeString(policy, "# comment\n\nallow Apache License, Version 2.0\n");

        assertThat(EngineLicenses.readPolicy(policy)).containsExactly("Apache License, Version 2.0");
    }
}

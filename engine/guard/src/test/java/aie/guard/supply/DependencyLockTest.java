package aie.guard.supply;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.guard.supply.DependencyLock.Entry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyLockTest {

    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @TempDir
    Path directory;

    @Test
    void aScanKeepsEveryDownloadAndDropsMavenBookkeeping() throws IOException {
        for (String file : List.of(
                "g/a/1/a-1.jar", "g/a/1/a-1.pom", "g/a/1/a-1-dist.zip",
                "g/a/1/a-1.jar.sha1", "g/a/1/a-1.pom.md5", "g/a/1/_remote.repositories",
                "g/a/1/a-1.jar.lastUpdated", "g/a/maven-metadata-central.xml", "g/a/resolver-status.properties")) {
            Files.createDirectories(directory.resolve(file).getParent());
            Files.writeString(directory.resolve(file), file);
        }

        assertThat(DependencyLock.scan(directory))
                .extracting(Entry::path)
                .containsExactly("g/a/1/a-1-dist.zip", "g/a/1/a-1.jar", "g/a/1/a-1.pom");
    }

    @Test
    void aScanHashesTheContent() throws IOException {
        Files.writeString(directory.resolve("file.jar"), "abc");

        assertThat(DependencyLock.scan(directory))
                .containsExactly(new Entry("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", "file.jar"));
    }

    @Test
    void whatIsWrittenReadsBackTheSame() throws IOException {
        List<Entry> entries = List.of(new Entry(HASH_A, "g/a/1/a-1.jar"), new Entry(HASH_B, "g/a/1/a-1.pom"));
        Path lock = directory.resolve("lock.txt");

        DependencyLock.write(lock, entries);

        assertThat(DependencyLock.read(lock)).isEqualTo(entries);
    }

    @Test
    void aBadLineIsRefused() throws IOException {
        Path lock = directory.resolve("lock.txt");
        Files.writeString(lock, "not-a-hash  g/a/1/a-1.jar\n");

        assertThatThrownBy(() -> DependencyLock.read(lock)).hasMessageContaining("S-8");
    }

    @Test
    void theSameFilesHaveNoDifferences() {
        List<Entry> entries = List.of(new Entry(HASH_A, "a.jar"));

        assertThat(DependencyLock.differences(entries, entries)).isEmpty();
    }

    @Test
    void everyKindOfDifferenceIsNamed() {
        List<Entry> locked = List.of(new Entry(HASH_A, "same.jar"), new Entry(HASH_A, "changed.jar"), new Entry(HASH_A, "gone.jar"));
        List<Entry> downloaded = List.of(new Entry(HASH_A, "same.jar"), new Entry(HASH_B, "changed.jar"), new Entry(HASH_A, "new.jar"));

        assertThat(DependencyLock.differences(locked, downloaded)).containsExactly(
                "S-8: downloaded but not in the lock: new.jar",
                "S-8: hash differs from the lock: changed.jar",
                "S-8: in the lock but not downloaded: gone.jar");
    }
}

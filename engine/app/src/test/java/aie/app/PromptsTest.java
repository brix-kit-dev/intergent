package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * M3-2: a published prompt version never changes (blueprint 8.7). prompts/versions.lock holds the SHA-256 of every
 * version, so a text that changes fails here, and a new text has to go into a new version with a line of its own.
 */
class PromptsTest {

    private static final Path PROMPTS = Path.of("../../prompts");
    private static final Path LOCK = PROMPTS.resolve("versions.lock");
    /** A version's file: task/family/vN.txt. */
    private static final Pattern VERSION = Pattern.compile("[a-z][a-z0-9_]*/[a-z][a-z0-9-]*/v[1-9][0-9]{0,3}\\.txt");
    private static final Pattern LINE = Pattern.compile("([0-9a-f]{64})  (\\S+)");

    /** Every file under prompts/ but the lock, by its path in the directory. */
    private static Map<String, Path> files() throws IOException {
        Map<String, Path> files = new TreeMap<>();
        try (Stream<Path> all = Files.walk(PROMPTS)) {
            all.filter(Files::isRegularFile).filter(file -> !file.equals(LOCK))
                    .forEach(file -> files.put(PROMPTS.relativize(file).toString().replace('\\', '/'), file));
        }
        return files;
    }

    /** The lock's lines, by path, without its comments. */
    private static Map<String, String> locked() throws IOException {
        Map<String, String> locked = new TreeMap<>();
        for (String line : Files.readAllLines(LOCK)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = LINE.matcher(line);
            assertThat(matcher.matches()).as("a line of versions.lock is the SHA-256, two spaces and the path: " + line).isTrue();
            assertThat(locked.put(matcher.group(2), matcher.group(1))).as("listed twice: " + matcher.group(2)).isNull();
        }
        return locked;
    }

    private static String sha256(Path file) throws IOException, NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    @Test
    void everyPromptIsAVersionOfATaskAndAFamily() throws IOException {
        assertThat(files().keySet()).isNotEmpty().allSatisfy(path -> assertThat(path).matches(VERSION));
    }

    /** A version that changed fails here; so does a new one without its line, and a line whose file has gone. */
    @Test
    void everyPublishedVersionKeepsItsText() throws IOException, NoSuchAlgorithmException {
        Map<String, Path> files = files();
        Map<String, String> locked = locked();

        assertThat(locked.keySet()).as("versions.lock lists every prompt and no other").containsExactlyElementsOf(files.keySet());
        for (Map.Entry<String, Path> file : files.entrySet()) {
            assertThat(sha256(file.getValue())).as(file.getKey() + " changed; a new text needs a new version")
                    .isEqualTo(locked.get(file.getKey()));
        }
    }

    /** The jar carries the versions and not the lock, which is for CI. */
    @Test
    void theJarHasTheVersionsAndNotTheLock() throws IOException {
        for (String path : files().keySet()) {
            assertThat(PromptsTest.class.getClassLoader().getResource("prompts/" + path)).as(path).isNotNull();
        }
        assertThat(PromptsTest.class.getClassLoader().getResource("prompts/versions.lock")).isNull();
        assertThat(LOCK).exists();
    }
}

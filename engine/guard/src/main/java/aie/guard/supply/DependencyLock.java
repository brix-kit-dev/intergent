package aie.guard.supply;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** S-8: every file a clean build downloads, with its hash, as recorded in the repo. */
public final class DependencyLock {

    /** Maven's own bookkeeping. Anything else in the repository counts as a download. */
    private static final Pattern BOOKKEEPING = Pattern.compile(
            "_remote\\.repositories|resolver-status\\.properties|maven-metadata.*|.*\\.(sha1|md5|lastUpdated)");
    private static final Pattern LINE = Pattern.compile("([0-9a-f]{64}) {2}(\\S+)");
    private static final String HEADER = """
            # S-8: every file a clean build downloads from Maven Central.
            # Generated. Change it with: engine/verify.sh --update-lock
            """;

    private DependencyLock() {
    }

    public record Entry(String sha256, String path) {
    }

    public static List<Entry> scan(Path repository) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> files = Files.walk(repository)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!BOOKKEEPING.matcher(file.getFileName().toString()).matches()) {
                    String path = repository.relativize(file).toString().replace(File.separatorChar, '/');
                    entries.add(new Entry(sha256(Files.readAllBytes(file)), path));
                }
            }
        }
        entries.sort(Comparator.comparing(Entry::path));
        return entries;
    }

    public static List<Entry> read(Path lock) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(lock)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = LINE.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("S-8: bad line in " + lock + ": " + line);
            }
            entries.add(new Entry(matcher.group(1), matcher.group(2)));
        }
        return entries;
    }

    public static void write(Path lock, List<Entry> entries) throws IOException {
        StringBuilder text = new StringBuilder(HEADER);
        entries.forEach(entry -> text.append(entry.sha256()).append("  ").append(entry.path()).append('\n'));
        Files.writeString(lock, text);
    }

    /** Empty when the build downloaded exactly what the lock records. */
    public static List<String> differences(List<Entry> locked, List<Entry> downloaded) {
        Map<String, String> expected = byPath(locked);
        Map<String, String> actual = byPath(downloaded);
        List<String> problems = new ArrayList<>();
        actual.forEach((path, sha256) -> {
            if (!expected.containsKey(path)) {
                problems.add("S-8: downloaded but not in the lock: " + path);
            } else if (!expected.get(path).equals(sha256)) {
                problems.add("S-8: hash differs from the lock: " + path);
            }
        });
        expected.keySet().stream()
                .filter(path -> !actual.containsKey(path))
                .forEach(path -> problems.add("S-8: in the lock but not downloaded: " + path));
        problems.sort(Comparator.naturalOrder());
        return problems;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> byPath(List<Entry> entries) {
        return entries.stream().collect(Collectors.toMap(Entry::path, Entry::sha256));
    }
}

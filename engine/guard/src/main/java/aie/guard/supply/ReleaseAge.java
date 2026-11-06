package aie.guard.supply;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** S-8: a version may enter the build only after it has been public for seven days. */
public final class ReleaseAge {

    public static final Duration MINIMUM = Duration.ofDays(7);

    /** group:artifact:version, the approval date, who approved it, why. */
    private static final Pattern EXCEPTION = Pattern.compile("(\\S+:\\S+:\\S+)\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\S+)\\s+(\\S.*)");

    private ReleaseAge() {
    }

    /** One file per version, keyed by coordinates. All files of a version are published together. */
    public static Map<String, String> versions(Collection<String> paths) {
        Map<String, String> versions = new TreeMap<>();
        paths.forEach(path -> versions.putIfAbsent(coordinates(path), path));
        return versions;
    }

    public static List<String> violations(
            Map<String, String> versions, Map<String, Instant> published, Set<String> approved, Instant now) {
        List<String> problems = new ArrayList<>();
        versions.forEach((coordinates, path) -> {
            if (approved.contains(coordinates)) {
                return;
            }
            Instant date = published.get(path);
            if (date == null) {
                problems.add("S-8: no release date for " + coordinates);
            } else if (date.plus(MINIMUM).isAfter(now)) {
                problems.add("S-8: released less than 7 days ago: " + coordinates + " (" + date + ")");
            }
        });
        return problems;
    }

    /** Emergency patches a person approved. Every line has to say who and why. */
    public static Set<String> readExceptions(Path file) throws IOException {
        Set<String> approved = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = EXCEPTION.matcher(line);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("S-8: bad line in " + file + ": " + line);
            }
            LocalDate.parse(matcher.group(2));
            approved.add(matcher.group(1));
        }
        return approved;
    }

    static String coordinates(String path) {
        String[] parts = path.split("/");
        if (parts.length < 4) {
            throw new IllegalArgumentException("S-8: not a repository path: " + path);
        }
        String group = String.join(".", List.of(parts).subList(0, parts.length - 3));
        return group + ":" + parts[parts.length - 3] + ":" + parts[parts.length - 2];
    }
}

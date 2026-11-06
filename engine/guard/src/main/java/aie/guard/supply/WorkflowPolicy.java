package aie.guard.supply;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S-8 for CI tools: every tool in a workflow is pinned to exact content and has waited seven days, and the workflow
 * holds no write access, no secrets and no way to skip a check.
 *
 * <p>The release date comes from the comment on the pin. Whoever bumps a pin writes it, so it is not checked online.
 */
public final class WorkflowPolicy {

    private static final Pattern USES = Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*(\\S+)(.*)$");
    private static final Pattern IMAGE = Pattern.compile("^\\s*image:\\s*(\\S+)(.*)$");
    private static final Pattern DOWNLOAD_HASH = Pattern.compile("^\\s*\\w+_SHA256:\\s*(\\S+)(.*)$");
    private static final Pattern COMMIT_PIN = Pattern.compile("[^@]+@[0-9a-f]{40}");
    private static final Pattern DIGEST_PIN = Pattern.compile("[^@]+@sha256:[0-9a-f]{64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RELEASED = Pattern.compile("#.*released (\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern READ_ONLY = Pattern.compile("(?m)^permissions:\\s*\\n\\s+contents: read\\s*$");
    private static final Pattern DOWNLOAD = Pattern.compile("\\b(curl|wget)\\b");

    private static final Map<Pattern, String> FORBIDDEN = Map.ofEntries(
            Map.entry(Pattern.compile("pull_request_target"), "pull_request_target runs with write access"),
            Map.entry(Pattern.compile("secrets\\."), "CI holds no secrets"),
            Map.entry(Pattern.compile(":\\s*write\\b|write-all"), "CI holds no write access"),
            Map.entry(Pattern.compile("continue-on-error"), "a failed step must fail the run"),
            Map.entry(Pattern.compile("\\|\\|\\s*true"), "a failed step must fail the run"),
            Map.entry(Pattern.compile("runs-on:.*-latest"), "runs-on must name a fixed version"),
            Map.entry(Pattern.compile("-Dskip|-Dmaven\\.test\\.|-Denforcer\\.skip|--fail-never|\\s-fn\\b"), "checks cannot be skipped"),
            Map.entry(Pattern.compile("\\|\\s*(ba|z)?sh\\b"), "a download is never piped into a shell"),
            Map.entry(Pattern.compile("^\\s*container:\\s*[^\\s#]"), "write container.image, so the digest rule sees it"),
            Map.entry(Pattern.compile("\\b(apt-get|apt|npm|npx|pip|pip3|brew|snap)\\s+(-\\S+\\s+)*(install|i|add)\\b"), "a package manager installs unpinned tools"),
            Map.entry(Pattern.compile("actions/cache|^\\s*cache:"), "a cache would hide what a clean build downloads"));

    private WorkflowPolicy() {
    }

    public static List<String> violations(String name, String text, LocalDate today) {
        List<String> problems = new ArrayList<>();
        String[] lines = text.split("\n");
        for (int number = 0; number < lines.length; number++) {
            String line = lines[number];
            String where = name + ":" + (number + 1);
            if (line.strip().startsWith("#")) {
                continue;
            }
            checkPin(USES, COMMIT_PIN, "a commit", line, where, today, problems);
            checkPin(IMAGE, DIGEST_PIN, "a digest", line, where, today, problems);
            checkPin(DOWNLOAD_HASH, SHA256, "a sha256 hash", line, where, today, problems);
            FORBIDDEN.forEach((pattern, reason) -> {
                if (pattern.matcher(line).find()) {
                    problems.add("S-8: " + where + ": " + reason);
                }
            });
        }
        if (!READ_ONLY.matcher(text).find()) {
            problems.add("S-8: " + name + ": permissions must be exactly contents: read");
        }
        if (text.contains("actions/checkout") && !text.contains("persist-credentials: false")) {
            problems.add("S-8: " + name + ": checkout must set persist-credentials: false");
        }
        if (DOWNLOAD.matcher(text).find() && !text.contains("sha256sum -c")) {
            problems.add("S-8: " + name + ": a download must be checked with sha256sum -c");
        }
        return problems;
    }

    private static void checkPin(
            Pattern kind, Pattern pin, String needs, String line, String where, LocalDate today, List<String> problems) {
        Matcher matcher = kind.matcher(line);
        if (!matcher.matches() || matcher.group(1).startsWith("./")) {
            return;
        }
        if (!pin.matcher(matcher.group(1)).matches()) {
            problems.add("S-8: " + where + ": not pinned to " + needs + ": " + matcher.group(1));
        }
        Matcher released = RELEASED.matcher(matcher.group(2));
        if (!released.find()) {
            problems.add("S-8: " + where + ": the pin needs a comment with 'released YYYY-MM-DD'");
            return;
        }
        try {
            if (LocalDate.parse(released.group(1)).plusDays(ReleaseAge.MINIMUM.toDays()).isAfter(today)) {
                problems.add("S-8: " + where + ": released less than 7 days ago: " + matcher.group(1));
            }
        } catch (DateTimeParseException e) {
            problems.add("S-8: " + where + ": bad release date: " + released.group(1));
        }
    }
}

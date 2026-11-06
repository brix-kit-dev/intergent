package aie.guard.supply;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S-8 for JavaScript: nothing to check until the first {@code package.json} appears, then every workspace must hold
 * the lock file, the pinned pnpm, the seven-day wait, and no permission for dependency build scripts.
 *
 * <p>The setting names follow pnpm 12. Check them against the version the repo pins when it picks one.
 */
public final class PnpmPolicy {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "node_modules", "target");
    private static final String MANIFEST = "package.json";
    private static final String WORKSPACE = "pnpm-workspace.yaml";
    private static final String LOCK = "pnpm-lock.yaml";
    private static final int SEVEN_DAYS_IN_MINUTES = 7 * 24 * 60;

    private static final Pattern PINNED_PNPM =
            Pattern.compile("\"packageManager\"\\s*:\\s*\"pnpm@\\d+\\.\\d+\\.\\d+\\+sha512\\.[0-9A-Za-z+/=]+\"");
    private static final Pattern RELEASE_AGE = Pattern.compile("(?m)^minimumReleaseAge:\\s*(\\d+)\\s*(#.*)?$");
    /** Each of these lets a dependency run a build script or skips the seven-day wait. */
    private static final Pattern FORBIDDEN_SETTING = Pattern.compile(
            "(?m)^(dangerouslyAllowAllBuilds:\\s*true|onlyBuiltDependencies:|permissions:|minimumReleaseAgeExclude:|allowBuilds:[ \\t]*[^\\s#])");
    private static final Pattern ALLOW_BUILDS = Pattern.compile("(?m)^allowBuilds:\\s*(#.*)?$((?:\\n[ \\t]+.*)*)");

    private PnpmPolicy() {
    }

    public static List<String> violations(Path repository) throws IOException {
        List<String> problems = new ArrayList<>();
        Set<Path> workspaces = new LinkedHashSet<>();
        for (Path manifest : manifests(repository)) {
            Path workspace = workspaceOf(manifest.getParent(), repository);
            if (workspace == null) {
                problems.add("S-8: no " + WORKSPACE + " above " + repository.relativize(manifest));
            } else {
                workspaces.add(workspace);
            }
        }
        for (Path workspace : workspaces) {
            checkWorkspace(workspace, repository.relativize(workspace).toString(), problems);
        }
        return problems;
    }

    private static void checkWorkspace(Path workspace, String name, List<String> problems) throws IOException {
        String where = " in workspace '" + name + "'";
        if (!Files.isRegularFile(workspace.resolve(LOCK))) {
            problems.add("S-8: no " + LOCK + where);
        }
        Path manifest = workspace.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest) || !PINNED_PNPM.matcher(Files.readString(manifest)).find()) {
            problems.add("S-8: packageManager must pin pnpm with a version and sha512 hash" + where);
        }
        String settings = Files.readString(workspace.resolve(WORKSPACE));
        Matcher age = RELEASE_AGE.matcher(settings);
        if (!age.find() || Long.parseLong(age.group(1)) < SEVEN_DAYS_IN_MINUTES) {
            problems.add("S-8: minimumReleaseAge must be at least " + SEVEN_DAYS_IN_MINUTES + where);
        }
        Matcher forbidden = FORBIDDEN_SETTING.matcher(settings);
        while (forbidden.find()) {
            problems.add("S-8: setting not allowed: " + forbidden.group(1) + where);
        }
        Matcher builds = ALLOW_BUILDS.matcher(settings);
        if (builds.find() && builds.group(2).matches("(?s).*:\\s*true\\b.*")) {
            problems.add("S-8: allowBuilds lets a dependency run its build script" + where);
        }
    }

    private static Path workspaceOf(Path directory, Path repository) {
        for (Path current = directory; current != null && current.startsWith(repository); current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(WORKSPACE))) {
                return current;
            }
        }
        return null;
    }

    private static List<Path> manifests(Path repository) throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(repository, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return SKIPPED_DIRECTORIES.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().equals(MANIFEST)) {
                    found.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }
}

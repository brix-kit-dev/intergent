package aie.guard.supply;

import aie.guard.supply.DependencyLock.Entry;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The S-8 and S-9 checks that need a finished clean build. {@code engine/verify.sh} runs this class.
 *
 * <p>The repository argument must hold only what that build downloaded, so never pass {@code ~/.m2}.
 */
public final class SupplyCheck {

    private static final int EXIT_FAILED = 1;
    private static final int EXIT_USAGE = 2;

    private SupplyCheck() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3 || !List.of("check", "write-lock").contains(args[0])) {
            System.err.println("Usage: SupplyCheck check|write-lock <engine directory> <clean repository>");
            System.exit(EXIT_USAGE);
        }
        Path engine = Path.of(args[1]);
        Path repository = Path.of(args[2]);
        Path lock = engine.resolve("dependency-lock.txt");
        if (args[0].equals("write-lock")) {
            DependencyLock.write(lock, DependencyLock.scan(repository));
            return;
        }
        List<String> problems = check(engine, lock, repository);
        problems.forEach(System.err::println);
        if (!problems.isEmpty()) {
            System.exit(EXIT_FAILED);
        }
    }

    private static List<String> check(Path engine, Path lock, Path repository) throws IOException {
        List<Entry> locked = DependencyLock.read(lock);
        List<String> problems = new ArrayList<>(DependencyLock.differences(locked, DependencyLock.scan(repository)));
        System.out.println("S-8 lock: " + locked.size() + " files checked");

        Map<String, String> versions = ReleaseAge.versions(locked.stream().map(Entry::path).toList());
        problems.addAll(ReleaseAge.violations(
                versions,
                CentralDates.published(versions.values()),
                ReleaseAge.readExceptions(engine.resolve("release-age-exceptions.txt")),
                Instant.now()));
        System.out.println("S-8 release age: " + versions.size() + " versions checked");

        problems.addAll(EngineLicenses.violations(
                engine.resolve("app/target/engine.jar"),
                locked,
                repository,
                EngineLicenses.readPolicy(engine.resolve("license-policy.txt"))));
        System.out.println("S-9 licenses: engine.jar checked");
        return problems;
    }
}

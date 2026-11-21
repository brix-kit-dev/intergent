package aie.app;

import static org.assertj.core.api.Assertions.assertThat;

import aie.adapters.storepg.TestDatabase;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the packaged engine.jar as an operator does, with java -jar and environment variables. Maven runs this class
 * in the integration-test phase, after package has built the jar; the other tests run the classes on Maven's class
 * path, which says nothing about the jar. Inside it, Flyway must find each module's scripts in a nested jar, and
 * Spring Boot must start from the jar's own layout. The tag keeps the default test run, before package, off it.
 */
@Tag("packaged-jar")
@Testcontainers
class PackagedJarIT {

    private static final Path JAR = Path.of("target/engine.jar");
    private static final Path ENGINE = Path.of("..").toAbsolutePath().normalize();
    /** A module's scripts, wherever a module keeps them, as the migrate role finds them on its class path. */
    private static final Pattern SCRIPT = Pattern.compile(".*/src/main/resources/db/migration/([a-z0-9]+)/V\\d+__[^/]+\\.sql");
    private static final Pattern APPLIED = Pattern.compile("(?m)^([a-z0-9]+): (\\d+) applied$");
    private static final Duration ROLE_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration API_START_TIMEOUT = Duration.ofSeconds(60);
    private static final AtomicInteger RUNS = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer DATABASE = TestDatabase.container();

    @TempDir
    static Path logs;

    private static Ran migrated;

    @BeforeAll
    static void migrateWithTheJar() throws IOException, InterruptedException {
        assertThat(JAR).as("mvn package builds the jar before this class runs").isRegularFile();
        assertThat(TestDatabase.createRoles(
                        DATABASE, "owner_password=" + TestDatabase.OWNER_PASSWORD, "app_password=" + TestDatabase.APP_PASSWORD)
                .getExitCode()).isZero();
        migrated = run(Map.of("ENGINE_OWNER_PASSWORD", TestDatabase.OWNER_PASSWORD), "--role=migrate");
    }

    /** The jar applies every script the source holds, module by module, and a second run applies none. */
    @Test
    void theJarAppliesEveryScriptOfEveryModule() throws IOException, InterruptedException {
        SortedMap<String, Long> scripts = scriptsPerModule();
        assertThat(scripts).as("the source has scripts to apply").containsKey("tenancy");

        assertThat(migrated.exitCode()).as(migrated::describe).isZero();
        assertThat(applied(migrated.out())).as(migrated::describe).isEqualTo(scripts);

        Ran again = run(Map.of("ENGINE_OWNER_PASSWORD", TestDatabase.OWNER_PASSWORD), "--role=migrate");
        assertThat(again.exitCode()).as(again::describe).isZero();
        assertThat(applied(again.out())).as(again::describe).containsOnlyKeys(scripts.keySet()).allSatisfy((module, count) -> assertThat(count).isZero());
    }

    /** The admin role prints the new ID and nothing else, so a script can take it. */
    @Test
    void theJarProvisionsATenant() throws IOException, InterruptedException {
        Ran created = run(Map.of("ENGINE_APP_PASSWORD", TestDatabase.APP_PASSWORD, "ENGINE_CELL_CODE", "ci"), "--role=admin", "create-tenant");

        assertThat(created.exitCode()).as(created::describe).isZero();
        assertThat(created.out()).as(created::describe).matches("ten_[0-9A-HJKMNP-TV-Z]{26}\\R");
    }

    /** The api role passes its self-check, answers on its port, refuses a call without a key, and stops on SIGTERM. */
    @Test
    void theJarServesTheApiAndStopsOnSigterm() throws IOException, InterruptedException {
        int port = freePort();
        byte[] sessionKey = new byte[32];
        new SecureRandom().nextBytes(sessionKey);
        byte[] auditMasterKey = new byte[32];
        new SecureRandom().nextBytes(auditMasterKey);
        Started api = start(Map.of(
                "ENGINE_APP_PASSWORD", TestDatabase.APP_PASSWORD,
                "ENGINE_SESSION_KEY", Base64.getEncoder().encodeToString(sessionKey),
                "ENGINE_AUDIT_MASTER_KEY", Base64.getEncoder().encodeToString(auditMasterKey),
                "DEPLOY_REGION", "jp",
                "SERVER_PORT", Integer.toString(port)), "--role=api");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        try {
            assertThat(healthy(client, api, port)).as(() -> api.describe()).isTrue();
            HttpResponse<String> refused = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/sessions"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"external_user_id\":\"u-1\"}"))
                    .timeout(Duration.ofSeconds(10))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(refused.statusCode()).as(refused::body).isEqualTo(401);
            assertThat(refused.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        } finally {
            api.process().destroy();
        }
        assertThat(api.process().waitFor(30, TimeUnit.SECONDS)).as(() -> api.describe()).isTrue();
        assertThat(api.process().exitValue()).as(() -> api.describe()).isEqualTo(143);
    }

    /** How many scripts each module has in the source. Built copies under target are not the source. */
    private static SortedMap<String, Long> scriptsPerModule() throws IOException {
        try (Stream<Path> files = Files.walk(ENGINE)) {
            return files.map(file -> ENGINE.relativize(file).toString().replace('\\', '/'))
                    .filter(path -> !path.contains("/target/") && !path.contains("/node_modules/"))
                    .map(SCRIPT::matcher)
                    .filter(Matcher::matches)
                    .collect(Collectors.groupingBy(matcher -> matcher.group(1), TreeMap::new, Collectors.counting()));
        }
    }

    /** What the migrate role reports, module by module. The jar's log lines around it do not count. */
    private static SortedMap<String, Long> applied(String out) {
        SortedMap<String, Long> applied = new TreeMap<>();
        Matcher line = APPLIED.matcher(out);
        while (line.find()) {
            applied.put(line.group(1), Long.parseLong(line.group(2)));
        }
        return applied;
    }

    private static boolean healthy(HttpClient client, Started api, int port) throws InterruptedException {
        Instant deadline = Instant.now().plus(API_START_TIMEOUT);
        HttpRequest health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthz")).timeout(Duration.ofSeconds(2)).build();
        while (Instant.now().isBefore(deadline) && api.process().isAlive()) {
            try {
                if (client.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return true;
                }
            } catch (IOException notYet) {
                // The server is not listening yet.
            }
            Thread.sleep(500);
        }
        return false;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Runs the jar until it exits. */
    private static Ran run(Map<String, String> settings, String... args) throws IOException, InterruptedException {
        Started started = start(settings, args);
        if (!started.process().waitFor(ROLE_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            started.process().destroyForcibly().waitFor();
            throw new AssertionError("engine.jar " + String.join(" ", args) + " did not finish in " + ROLE_TIMEOUT + "\n" + started.describe());
        }
        return new Ran(started.process().exitValue(), Files.readString(started.out()), started);
    }

    /**
     * Starts the jar with the database's address and these settings, and nothing else of the engine's from this
     * machine's environment. Its output goes to files, so nothing it prints reaches the CI log unless a test fails.
     */
    private static Started start(Map<String, String> settings, String... args) throws IOException {
        int run = RUNS.incrementAndGet();
        Path out = logs.resolve(run + ".out");
        Path err = logs.resolve(run + ".err");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", JAR.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        builder.environment().keySet().removeIf(name -> name.startsWith("ENGINE_") || name.equals("SERVER_PORT"));
        builder.environment().put("ENGINE_DATABASE_URL", DATABASE.getJdbcUrl());
        builder.environment().putAll(settings);
        return new Started(builder.start(), String.join(" ", args), out, err);
    }

    /** A run of the jar and where its output went. */
    private record Started(Process process, String args, Path out, Path err) {

        /** The end of what the run printed, for a failing assertion. */
        String describe() {
            return "engine.jar " + args + "\n--- stdout ---\n" + tail(out) + "\n--- stderr ---\n" + tail(err);
        }

        private static String tail(Path file) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                return text.length() <= 4000 ? text : text.substring(text.length() - 4000);
            } catch (IOException e) {
                return "(unreadable: " + e.getMessage() + ")";
            }
        }
    }

    /** A finished run: its exit code and standard output. */
    private record Ran(int exitCode, String out, Started started) {

        String describe() {
            return "exit " + exitCode + ", " + started.describe();
        }
    }
}

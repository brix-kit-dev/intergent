package aie.guard.supply;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs against a fake Maven Central on this machine, which answers each file the way a test plans. */
class CentralDatesTest {

    private static final String LAST_MODIFIED = "Tue, 03 Jun 2025 10:15:30 GMT";
    private static final Instant PUBLISHED = Instant.parse("2025-06-03T10:15:30Z");
    /** Short, so that a test does not wait 5 seconds between tries. */
    private static final Duration SHORT_DELAY = Duration.ofMillis(1);
    /** Long enough that a retry sent without waiting reaches the fake well before it, even on a busy machine. */
    private static final Duration MEASURED_DELAY = Duration.ofMillis(500);
    /** Planned in place of a status: drop the connection without an answer. */
    private static final int DROP = -1;
    private static final String GIVE_UP = "so no file is tried again";

    private final FakeCentral central = new FakeCentral();
    private final ByteArrayOutputStream logged = new ByteArrayOutputStream();

    @AfterEach
    void stopTheFake() {
        central.close();
    }

    /** Sleeps between tries, as the build does. */
    private Map<String, Instant> published(List<String> paths, Duration retryDelay) {
        return CentralDates.published(paths, central.address(), retryDelay, new PrintStream(logged, true, StandardCharsets.UTF_8));
    }

    /** Waits between tries the way the test says, such as noting each delay and going on at once. */
    private Map<String, Instant> published(List<String> paths, Duration retryDelay, CentralDates.Sleeper sleeper) {
        return CentralDates.published(paths, central.address(), retryDelay, sleeper, new PrintStream(logged, true, StandardCharsets.UTF_8));
    }

    private Map<String, Instant> published(List<String> paths) {
        return published(paths, SHORT_DELAY);
    }

    private String log() {
        return logged.toString(StandardCharsets.UTF_8);
    }

    private static List<String> files(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "f" + i + ".pom").toList();
    }

    @Test
    void aFileGetsTheDateCentralGives() {
        assertThat(published(List.of("a.pom"))).isEqualTo(Map.of("a.pom", PUBLISHED));
        assertThat(central.requests("a.pom")).isEqualTo(1);
        assertThat(log()).isEmpty();
    }

    @Test
    void aTemporaryFailureIsTriedAgainUpToTenTimes() {
        central.plan("a.pom", Collections.nCopies(CentralDates.RETRIES, 503));

        assertThat(published(List.of("a.pom"))).isEqualTo(Map.of("a.pom", PUBLISHED));
        assertThat(central.requests("a.pom")).isEqualTo(11);
    }

    @Test
    void afterTenRetriesTheFileStaysUndated() {
        central.plan("a.pom", Collections.nCopies(CentralDates.RETRIES + 1, 503));

        assertThat(published(List.of("a.pom"))).isEmpty();
        assertThat(central.requests("a.pom")).isEqualTo(11);
        assertThat(log()).contains("Maven Central still fails for a.pom after 10 retries, " + GIVE_UP);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void everyAnswerMavenTriesAgainIsTriedAgain(int status) {
        central.plan("a.pom", List.of(status));

        assertThat(published(List.of("a.pom"))).isEqualTo(Map.of("a.pom", PUBLISHED));
        assertThat(central.requests("a.pom")).isEqualTo(2);
        assertThat(log()).isEqualTo("HTTP " + status + " for a.pom" + System.lineSeparator());
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 410, 501})
    void anyOtherAnswerIsFinal(int status) {
        central.plan("a.pom", List.of(status));

        assertThat(published(List.of("a.pom"))).isEmpty();
        assertThat(central.requests("a.pom")).isEqualTo(1);
        assertThat(log()).isEqualTo("HTTP " + status + " for a.pom" + System.lineSeparator());
    }

    /** The JDK client sends a HEAD once more by itself when the connection drops, so it takes two drops in a row. */
    @Test
    void aDroppedConnectionIsTriedAgain() {
        central.plan("a.pom", List.of(DROP, DROP));

        assertThat(published(List.of("a.pom"))).isEqualTo(Map.of("a.pom", PUBLISHED));
        assertThat(log()).startsWith("java.io.IOException").endsWith(" for a.pom" + System.lineSeparator());
    }

    /**
     * The build waits the same 5 seconds before every retry, not longer each time. The test notes each wait the code asks
     * for and goes on at once, rather than timing the requests, so a busy machine cannot fail it (point 45).
     */
    @Test
    void everyRetryWaitsTheSameDelay() {
        central.plan("a.pom", List.of(503, 503, 503));
        Queue<Duration> waits = new ConcurrentLinkedQueue<>();

        assertThat(published(List.of("a.pom"), CentralDates.RETRY_DELAY, waits::add)).isEqualTo(Map.of("a.pom", PUBLISHED));

        assertThat(waits).containsExactly(CentralDates.RETRY_DELAY, CentralDates.RETRY_DELAY, CentralDates.RETRY_DELAY);
        assertThat(central.requests("a.pom")).isEqualTo(4);
    }

    /**
     * The build's sleep really waits: each retry reaches Central at least the delay after the try before. The gaps are
     * measured where Central sees the requests (code point 33), and only this lower bound is checked, because a busy
     * machine can only make them longer (point 45).
     */
    @Test
    void aRetryReallyWaitsTheDelay() {
        central.plan("a.pom", List.of(503, 503, 503));

        assertThat(published(List.of("a.pom"), MEASURED_DELAY)).containsKey("a.pom");

        assertThat(central.gaps("a.pom")).hasSize(3).allSatisfy(gap -> assertThat(gap).isGreaterThanOrEqualTo(MEASURED_DELAY));
    }

    /** The files after the first 16 wait in a queue, so they start only once a file has given up. */
    @Test
    void onceAFileRunsOutOfTriesNoFileIsTriedAgain() {
        List<String> paths = files(CentralDates.PARALLEL_REQUESTS + 4);
        paths.forEach(path -> central.plan(path, Collections.nCopies(CentralDates.RETRIES + 1, 503)));

        assertThat(published(paths)).isEmpty();
        assertThat(paths.subList(CentralDates.PARALLEL_REQUESTS, paths.size()))
                .allSatisfy(path -> assertThat(central.requests(path)).isZero());
        assertThat(log()).containsOnlyOnce(GIVE_UP);
    }

    @Test
    void aFinalAnswerDoesNotStopTheOtherFiles() {
        List<String> paths = files(CentralDates.PARALLEL_REQUESTS + 4);
        central.plan(paths.get(0), List.of(404));

        assertThat(published(paths)).hasSize(paths.size() - 1).doesNotContainKey(paths.get(0));
        assertThat(log()).doesNotContain(GIVE_UP);
    }

    /** verify.sh gives Maven these numbers, and the release dates come from the same Central (code point 22). */
    @Test
    void theTriesMatchTheBuild() throws IOException, URISyntaxException {
        Path testClasses = Path.of(CentralDatesTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String verify = Files.readString(testClasses.getParent().getParent().getParent().resolve("verify.sh"));

        assertThat(verify)
                .contains("serviceUnavailableRetryStrategy.retryInterval=" + CentralDates.RETRY_DELAY.toMillis() + "\n")
                .contains("serviceUnavailableRetryStrategy.maxRetries=" + CentralDates.RETRIES + "\n");
        Matcher listed = Pattern.compile("a ([0-9, or]+) is retried").matcher(verify);
        assertThat(listed.find()).isTrue();
        assertThat(Pattern.compile("\\d{3}").matcher(listed.group(1)).results().map(found -> Integer.valueOf(found.group())).collect(toSet()))
                .isEqualTo(CentralDates.TEMPORARY);
    }

    /** Answers HEAD requests: first what a test planned for the file, then 200 with {@link #LAST_MODIFIED}. */
    private static final class FakeCentral implements AutoCloseable {

        private static final String ROOT = "/maven2/";

        private final HttpServer server;
        private final Map<String, Queue<Integer>> plans = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        private final Map<String, Queue<Long>> arrivals = new ConcurrentHashMap<>();

        FakeCentral() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            server.createContext(ROOT, this::answer);
            server.start();
        }

        String address() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + ROOT;
        }

        void plan(String path, List<Integer> answers) {
            plans.put(path, new ConcurrentLinkedQueue<>(answers));
        }

        int requests(String path) {
            AtomicInteger count = requests.get(path);
            return count == null ? 0 : count.get();
        }

        /** The time between one request for the path and the next. */
        List<Duration> gaps(String path) {
            List<Long> times = List.copyOf(arrivals.getOrDefault(path, new ConcurrentLinkedQueue<>()));
            return IntStream.range(1, times.size()).mapToObj(i -> Duration.ofNanos(times.get(i) - times.get(i - 1))).toList();
        }

        private void answer(HttpExchange exchange) throws IOException {
            long arrived = System.nanoTime();
            String path = exchange.getRequestURI().getPath().substring(ROOT.length());
            requests.computeIfAbsent(path, any -> new AtomicInteger()).incrementAndGet();
            arrivals.computeIfAbsent(path, any -> new ConcurrentLinkedQueue<>()).add(arrived);
            Queue<Integer> planned = plans.get(path);
            Integer status = planned == null ? null : planned.poll();
            if (status == null) {
                exchange.getResponseHeaders().set("Last-Modified", LAST_MODIFIED);
                exchange.sendResponseHeaders(200, -1);
            } else if (status != DROP) {
                exchange.sendResponseHeaders(status, -1);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}

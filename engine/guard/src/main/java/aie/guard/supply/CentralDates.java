package aie.guard.supply;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads when Maven Central published each file. A file it cannot date stays out of the result.
 *
 * <p>Central sometimes fails for a moment, so a failure that may be temporary is tried again the way {@code verify.sh}
 * has Maven try a download again: every 5 seconds, up to 10 times. A broken connection counts as one. Once a file runs
 * out of tries, Central is taken to be down: no file is tried after that, and every file left without a date fails the
 * check. Otherwise an outage would hold the build for half an hour, past the 25 minutes CI allows.
 */
final class CentralDates {

    private static final String CENTRAL = "https://repo1.maven.org/maven2/";
    static final int PARALLEL_REQUESTS = 16;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /** The same as {@code verify.sh} gives Maven. */
    static final int RETRIES = 10;
    static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    /** The answers {@code verify.sh} has Maven try again. Any other answer, such as 404, is final. */
    static final Set<Integer> TEMPORARY = Set.of(408, 429, 500, 502, 503, 504);

    /** How a request waits before it is tried again. The build sleeps; a test can note the delay instead (point 45). */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    private CentralDates() {
    }

    static Map<String, Instant> published(Collection<String> paths) {
        return published(paths, CENTRAL, RETRY_DELAY, System.err);
    }

    /** Sleeps the delay before each retry, as the build does. */
    static Map<String, Instant> published(
            Collection<String> paths, String central, Duration retryDelay, PrintStream log) {
        return published(paths, central, retryDelay, Thread::sleep, log);
    }

    static Map<String, Instant> published(
            Collection<String> paths, String central, Duration retryDelay, Sleeper sleeper, PrintStream log) {
        Map<String, Instant> dates = new ConcurrentHashMap<>();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
                ExecutorService requests = Executors.newFixedThreadPool(PARALLEL_REQUESTS)) {
            Run run = new Run(client, central, retryDelay, sleeper, log, dates, new AtomicBoolean());
            paths.forEach(path -> requests.execute(() -> run.fetch(path)));
        }
        return dates;
    }

    private static Instant parse(String httpDate) {
        return ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    }

    /** What the requests of one call share. {@code gaveUp} turns true once a file has run out of tries. */
    private record Run(
            HttpClient client,
            String central,
            Duration retryDelay,
            Sleeper sleeper,
            PrintStream log,
            Map<String, Instant> dates,
            AtomicBoolean gaveUp) {

        void fetch(String path) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(central + path))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(TIMEOUT)
                    .build();
            try {
                for (int attempt = 0; attempt <= RETRIES; attempt++) {
                    if (attempt > 0) {
                        sleeper.sleep(retryDelay);
                    }
                    if (gaveUp.get() || !failedForNow(request, path)) {
                        return;
                    }
                }
                if (gaveUp.compareAndSet(false, true)) {
                    log.println("Maven Central still fails for " + path + " after " + RETRIES
                            + " retries, so no file is tried again");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** Asks once, and returns whether it failed in a way that may be temporary. */
        private boolean failedForNow(HttpRequest request, String path) throws InterruptedException {
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    response.headers().firstValue("Last-Modified").ifPresent(date -> dates.put(path, parse(date)));
                    return false;
                }
                log.println("HTTP " + response.statusCode() + " for " + path);
                return TEMPORARY.contains(response.statusCode());
            } catch (IOException e) {
                log.println(e + " for " + path);
                return true;
            }
        }
    }
}

package aie.guard.supply;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reads when Maven Central published each file. A file it cannot date stays out of the result. */
final class CentralDates {

    private static final String CENTRAL = "https://repo1.maven.org/maven2/";
    private static final int PARALLEL_REQUESTS = 16;
    private static final int ATTEMPTS = 3;
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);

    private CentralDates() {
    }

    static Map<String, Instant> published(Collection<String> paths) {
        Map<String, Instant> dates = new ConcurrentHashMap<>();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
                ExecutorService requests = Executors.newFixedThreadPool(PARALLEL_REQUESTS)) {
            List<Runnable> tasks = paths.stream().<Runnable>map(path -> () -> fetch(client, path, dates)).toList();
            tasks.forEach(requests::execute);
        }
        return dates;
    }

    private static void fetch(HttpClient client, String path, Map<String, Instant> dates) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(CENTRAL + path))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .timeout(TIMEOUT)
                .build();
        try {
            for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                if (read(client, request, path, dates)) {
                    return;
                }
                if (attempt < ATTEMPTS) {
                    Thread.sleep(RETRY_DELAY.multipliedBy(attempt));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean read(HttpClient client, HttpRequest request, String path, Map<String, Instant> dates)
            throws InterruptedException {
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 200) {
                response.headers().firstValue("Last-Modified").ifPresent(date -> dates.put(path, parse(date)));
                return true;
            }
            System.err.println("HTTP " + response.statusCode() + " for " + path);
        } catch (IOException e) {
            System.err.println(e + " for " + path);
        }
        return false;
    }

    private static Instant parse(String httpDate) {
        return ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    }
}

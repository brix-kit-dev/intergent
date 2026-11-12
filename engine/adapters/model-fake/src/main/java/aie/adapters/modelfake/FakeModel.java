package aie.adapters.modelfake;

import aie.ports.ModelPort;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model {@code fake} of blueprint 8.3. It answers in this process, so no data leaves it, and it answers the same way
 * every time: numbered pieces, "Piece 1 of 8. " and so on, so that a missing or repeated piece shows at once. It waits
 * before each piece, and counts characters as tokens.
 *
 * <p>Tests and demos steer it with one bracket in the last user turn, such as {@code [fake pieces=3 delay=1s]}:
 * <ul>
 *   <li>{@code pieces}: how many, 1 to 1000;
 *   <li>{@code delay}: the wait before each piece, such as {@code 200ms} or {@code 2s}, up to 60 seconds;
 *   <li>{@code fail-after}: fail after that many pieces;
 *   <li>{@code hang-after}: stop after that many pieces until the answer is cancelled or out of time.
 * </ul>
 * A bracket it cannot read fails the answer and says why.
 */
public final class FakeModel implements ModelPort {

    /** Its ID in the gateway's registry. */
    public static final String MODEL = "fake";
    static final int PIECES = 8;
    static final Duration DELAY = Duration.ofMillis(50);
    private static final int MAX_PIECES = 1000;
    private static final Duration MAX_DELAY = Duration.ofSeconds(60);
    /** How often a wait looks at the cancel and the clock, which bounds how late a cancel is noticed. */
    private static final Duration SLICE = Duration.ofMillis(10);
    private static final Pattern BRACKET = Pattern.compile("\\[fake(?:\\s+([^\\]]*))?]");
    private static final Pattern SETTING = Pattern.compile("([a-z-]+)=(\\S+)");
    private static final Pattern DURATION = Pattern.compile("(\\d{1,6})(ms|s)");

    private final Duration delay;

    public FakeModel() {
        this(DELAY);
    }

    /** With another default wait before each piece, so tests can run fast. */
    FakeModel(Duration delay) {
        this.delay = delay;
    }

    @Override
    public Result generate(Request request, Output output) {
        String said = lastUserTurn(request);
        long input = request.turns().stream().mapToLong(turn -> characters(turn.text())).sum();
        Settings settings;
        try {
            settings = Settings.read(said, delay);
        } catch (IllegalArgumentException e) {
            return new Failed(e.getMessage(), 0, new Usage(input, 0));
        }
        long deadline = System.nanoTime() + request.timeout().toNanos();
        long written = 0;
        for (int piece = 1; piece <= settings.pieces(); piece++) {
            Usage usage = new Usage(input, written);
            if (settings.failAfter() == piece - 1) {
                return new Failed("The fake model was told to fail after " + (piece - 1) + " pieces", piece - 1, usage);
            }
            Duration wait = settings.hangAfter() == piece - 1 ? Duration.ofNanos(Long.MAX_VALUE) : settings.delay();
            Waited waited = await(wait, deadline, request.cancellation());
            switch (waited) {
                case CANCELLED -> {
                    return new Cancelled(usage);
                }
                case OUT_OF_TIME -> {
                    return new Failed("The fake model had no answer within " + request.timeout(), piece - 1, usage);
                }
                case INTERRUPTED -> {
                    return new Failed("The fake model was interrupted", piece - 1, usage);
                }
                case DONE -> {
                    String text = "Piece " + piece + " of " + settings.pieces() + ". ";
                    output.text(text);
                    written += characters(text);
                }
            }
        }
        return new Completed(new Usage(input, written));
    }

    private static String lastUserTurn(Request request) {
        if (!MODEL.equals(request.model())) {
            throw new IllegalArgumentException("The fake model is " + MODEL + ", not " + request.model());
        }
        if (request.timeout() == null || request.timeout().isNegative() || request.timeout().isZero()) {
            throw new IllegalArgumentException("A request needs a timeout above zero, not " + request.timeout());
        }
        if (request.cancellation() == null) {
            throw new IllegalArgumentException("A request needs a cancellation, even one that never asks to stop");
        }
        List<Turn> turns = request.turns();
        if (turns == null || turns.isEmpty() || turns.getLast().role() != Role.USER || turns.getLast().text() == null) {
            throw new IllegalArgumentException("A request ends with the user's turn");
        }
        return turns.getLast().text();
    }

    private static long characters(String text) {
        return text.codePointCount(0, text.length());
    }

    private enum Waited { DONE, CANCELLED, OUT_OF_TIME, INTERRUPTED }

    /** Waits a while in short steps, so that a cancel or the deadline ends the wait soon after it comes. */
    private static Waited await(Duration wait, long deadline, Cancellation cancellation) {
        long start = System.nanoTime();
        try {
            while (true) {
                if (cancellation.requested()) {
                    return Waited.CANCELLED;
                }
                long now = System.nanoTime();
                if (now - start >= wait.toNanos()) {
                    return Waited.DONE;
                }
                if (now - deadline >= 0) {
                    return Waited.OUT_OF_TIME;
                }
                long left = Math.min(wait.toNanos() - (now - start), deadline - now);
                Thread.sleep(Duration.ofNanos(Math.min(left, SLICE.toNanos())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Waited.INTERRUPTED;
        }
    }

    /** What one bracket asks for. A count of -1 means it does not ask for that. */
    private record Settings(int pieces, Duration delay, int failAfter, int hangAfter) {

        static Settings read(String said, Duration delay) {
            Matcher bracket = BRACKET.matcher(said);
            if (!bracket.find()) {
                return new Settings(PIECES, delay, -1, -1);
            }
            String inside = bracket.group(1) == null ? "" : bracket.group(1).trim();
            if (bracket.find()) {
                throw new IllegalArgumentException("The fake model takes one [fake ...] bracket, not two");
            }
            int pieces = PIECES;
            int failAfter = -1;
            int hangAfter = -1;
            Duration wait = delay;
            for (String part : inside.split("\\s+")) {
                if (part.isEmpty()) {
                    continue;
                }
                Matcher setting = SETTING.matcher(part);
                if (!setting.matches()) {
                    throw new IllegalArgumentException("The fake model cannot read \"" + part + "\"; write name=value");
                }
                String value = setting.group(2);
                switch (setting.group(1)) {
                    case "pieces" -> pieces = count(value, 1, MAX_PIECES, "pieces");
                    case "delay" -> wait = duration(value);
                    case "fail-after" -> failAfter = count(value, 0, MAX_PIECES, "fail-after");
                    case "hang-after" -> hangAfter = count(value, 0, MAX_PIECES, "hang-after");
                    default -> throw new IllegalArgumentException(
                            "The fake model knows pieces, delay, fail-after and hang-after, not " + setting.group(1));
                }
            }
            return new Settings(pieces, wait, failAfter, hangAfter);
        }

        private static int count(String value, int least, int most, String name) {
            if (!value.matches("\\d{1,4}") || Integer.parseInt(value) < least || Integer.parseInt(value) > most) {
                throw new IllegalArgumentException(
                        "The fake model's " + name + " is a whole number from " + least + " to " + most + ", not " + value);
            }
            return Integer.parseInt(value);
        }

        private static Duration duration(String value) {
            Matcher duration = DURATION.matcher(value.toLowerCase(Locale.ROOT));
            Duration parsed = !duration.matches() ? null : duration.group(2).equals("ms")
                    ? Duration.ofMillis(Long.parseLong(duration.group(1)))
                    : Duration.ofSeconds(Long.parseLong(duration.group(1)));
            if (parsed == null || parsed.compareTo(MAX_DELAY) > 0) {
                throw new IllegalArgumentException(
                        "The fake model's delay is milliseconds or seconds, such as 200ms or 2s, up to 60s, not " + value);
            }
            return parsed;
        }
    }
}

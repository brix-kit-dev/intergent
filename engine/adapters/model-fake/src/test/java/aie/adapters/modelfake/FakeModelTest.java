package aie.adapters.modelfake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.ports.ModelPort;
import aie.ports.ModelPort.Cancellation;
import aie.ports.ModelPort.Cancelled;
import aie.ports.ModelPort.Completed;
import aie.ports.ModelPort.Failed;
import aie.ports.ModelPort.Request;
import aie.ports.ModelPort.Result;
import aie.ports.ModelPort.Role;
import aie.ports.ModelPort.Turn;
import aie.ports.ModelPort.Usage;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A test that waits too long fails, rather than holding up the build: a broken timeout or cancel would hang. Each test
 * runs in a thread of its own, which the timeout stops waiting for. Interrupting the test is not enough, because a
 * broken wait can spin without ever looking at the interrupt: a negative {@code Thread.sleep} returns at once.
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class FakeModelTest {

    private static final Cancellation NEVER = () -> false;
    private static final Duration LONG = Duration.ofSeconds(30);
    /** What the gateway sends as the task's prompt (M3-2). */
    private static final String INSTRUCTIONS = "Answer the customers of a business.";

    /** Fast by default, so that only the tests about time wait. */
    private final ModelPort model = new FakeModel(Duration.ofMillis(1));
    private final List<String> pieces = new CopyOnWriteArrayList<>();
    private final List<Long> arrivals = new CopyOnWriteArrayList<>();

    private static Request request(String said, Duration timeout, Cancellation cancellation) {
        return new Request(FakeModel.MODEL, INSTRUCTIONS, List.of(new Turn(Role.USER, said)), timeout, cancellation);
    }

    private Result generate(Request request) {
        return model.generate(request, piece -> {
            pieces.add(piece);
            arrivals.add(System.nanoTime());
        });
    }

    private Result generate(String said) {
        return generate(request(said, LONG, NEVER));
    }

    private static String numbered(int count) {
        StringBuilder text = new StringBuilder();
        for (int piece = 1; piece <= count; piece++) {
            text.append("Piece ").append(piece).append(" of ").append(count).append(". ");
        }
        return text.toString();
    }

    @Test
    void itAnswersInNumberedPieces() {
        Result result = generate("Hello");

        assertThat(pieces).hasSize(FakeModel.PIECES).startsWith("Piece 1 of 8. ").endsWith("Piece 8 of 8. ");
        assertThat(String.join("", pieces)).isEqualTo(numbered(8));
        assertThat(result).isEqualTo(new Completed(new Usage(5, numbered(8).length())));
    }

    @Test
    void itCountsTheCharactersOfEveryTurnAsInput() {
        Request request = new Request(FakeModel.MODEL, INSTRUCTIONS, List.of(
                new Turn(Role.USER, "こんにちは"), new Turn(Role.ASSISTANT, "Hi"), new Turn(Role.USER, "😀 ok")), LONG, NEVER);

        assertThat(generate(request).usage().inputTokens()).isEqualTo(5 + 2 + 4);
    }

    @Test
    void itAnswersInAsManyPiecesAsItIsTold() {
        assertThat(generate("[fake pieces=3] Hello")).isInstanceOf(Completed.class);

        assertThat(pieces).containsExactly("Piece 1 of 3. ", "Piece 2 of 3. ", "Piece 3 of 3. ");
    }

    @Test
    void itFailsAfterThePiecesItIsTold() {
        Result result = generate("[fake pieces=5 fail-after=2]");

        assertThat(pieces).hasSize(2);
        assertThat(result).isInstanceOfSatisfying(Failed.class, failed -> {
            assertThat(failed.pieces()).isEqualTo(2);
            assertThat(failed.reason()).contains("after 2 pieces");
            assertThat(failed.usage().outputTokens()).isEqualTo(numbered(5).substring(0, 28).length());
        });
    }

    @Test
    void itCanFailBeforeTheFirstPiece() {
        assertThat(generate("[fake fail-after=0]")).isInstanceOfSatisfying(Failed.class, failed -> assertThat(failed.pieces()).isZero());
        assertThat(pieces).isEmpty();
    }

    @Test
    void aHungAnswerEndsWhenTheTimeRunsOut() {
        long start = System.nanoTime();
        Result result = generate(request("[fake hang-after=1]", Duration.ofMillis(300), NEVER));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(pieces).containsExactly("Piece 1 of 8. ");
        assertThat(result).isInstanceOfSatisfying(Failed.class, failed -> {
            assertThat(failed.pieces()).isEqualTo(1);
            assertThat(failed.reason()).contains("PT0.3S");
        });
        assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(300)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void itWaitsBeforeEachPiece() {
        long start = System.nanoTime();
        generate("[fake pieces=3 delay=200ms]");

        assertThat(Duration.ofNanos(arrivals.getFirst() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
        for (int piece = 1; piece < arrivals.size(); piece++) {
            assertThat(Duration.ofNanos(arrivals.get(piece) - arrivals.get(piece - 1))).isGreaterThanOrEqualTo(Duration.ofMillis(200));
        }
    }

    /** A cancel during a wait ends it at once, long before the next piece was due. */
    @Test
    void aCancelStopsTheAnswerSoon() throws InterruptedException {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicReference<Result> result = new AtomicReference<>();
        Thread answer = Thread.ofVirtual().start(() -> result.set(generate(request("[fake pieces=3 delay=1s]", LONG, cancel::get))));
        long giveUp = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (pieces.isEmpty() && System.nanoTime() < giveUp) {
            Thread.sleep(5);
        }
        assertThat(pieces).as("the first piece").hasSize(1);
        Thread.sleep(100);
        long cancelled = System.nanoTime();
        cancel.set(true);
        answer.join(Duration.ofSeconds(5));

        assertThat(result.get()).isInstanceOfSatisfying(Cancelled.class, ended -> assertThat(ended.usage().outputTokens())
                .isEqualTo("Piece 1 of 3. ".length()));
        assertThat(pieces).containsExactly("Piece 1 of 3. ");
        assertThat(Duration.ofNanos(System.nanoTime() - cancelled)).isLessThan(Duration.ofMillis(500));
    }

    @Test
    void aCancelBeforeTheFirstPieceGeneratesNothing() {
        assertThat(generate(request("Hello", LONG, () -> true))).isEqualTo(new Cancelled(new Usage(5, 0)));
        assertThat(pieces).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[fake pieces=0]", "[fake pieces=1001]", "[fake pieces=many]", "[fake delay=61s]", "[fake delay=5m]",
        "[fake colour=red]", "[fake pieces]", "[fake pieces=3] and [fake delay=1s]"})
    void aBracketItCannotReadFailsTheAnswerAndSaysWhy(String said) {
        assertThat(generate(said)).isInstanceOfSatisfying(Failed.class, failed -> {
            assertThat(failed.pieces()).isZero();
            assertThat(failed.reason()).startsWith("The fake model");
        });
        assertThat(pieces).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[fakery] Hello", "[faked pieces=3]", "fake pieces=3"})
    void otherTextIsJustText(String said) {
        assertThat(generate(said)).isInstanceOf(Completed.class);
        assertThat(pieces).hasSize(FakeModel.PIECES);
    }

    @Test
    void anEmptyBracketChangesNothing() {
        assertThat(generate("[fake] Hello")).isInstanceOf(Completed.class);
        assertThat(pieces).hasSize(FakeModel.PIECES);
    }

    @Test
    void aMistakeInTheRequestIsAnException() {
        Turn hello = new Turn(Role.USER, "Hello");
        assertThatThrownBy(() -> generate(new Request("other", INSTRUCTIONS, List.of(hello), LONG, NEVER))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generate(new Request(FakeModel.MODEL, INSTRUCTIONS, List.of(hello), Duration.ZERO, NEVER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generate(new Request(FakeModel.MODEL, INSTRUCTIONS, List.of(hello), LONG, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generate(new Request(FakeModel.MODEL, INSTRUCTIONS, List.of(), LONG, NEVER))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generate(new Request(FakeModel.MODEL, INSTRUCTIONS, List.of(new Turn(Role.ASSISTANT, "Hi")), LONG, NEVER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> generate(new Request(FakeModel.MODEL, null, List.of(hello), LONG, NEVER)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("A request needs its instructions, the prompt of the task");
    }

    /** M3-2: echo=instructions answers with the prompt the gateway sent, in one piece, so a test can see it arrived. */
    @Test
    void itEchoesTheInstructionsWhenAsked() {
        Result result = generate("[fake echo=instructions] Hello");

        assertThat(pieces).containsExactly(INSTRUCTIONS);
        assertThat(result).isEqualTo(new Completed(new Usage("[fake echo=instructions] Hello".length(), INSTRUCTIONS.length())));
    }

    /** M3-4-2: echo=turns answers with the turns the gateway sent, one per line, so a test can see what the model got. */
    @Test
    void itEchoesTheTurnsWhenAsked() {
        List<Turn> turns = List.of(new Turn(Role.USER, "My phone is [phone]"), new Turn(Role.ASSISTANT, "Noted."),
                new Turn(Role.USER, "[fake echo=turns] And my mail is [email]"));
        String echoed = "user: My phone is [phone]\nassistant: Noted.\nuser: [fake echo=turns] And my mail is [email]";

        Result result = generate(new Request(FakeModel.MODEL, INSTRUCTIONS, turns, LONG, NEVER));

        assertThat(pieces).containsExactly(echoed);
        assertThat(result).isEqualTo(new Completed(new Usage(
                turns.stream().mapToLong(turn -> turn.text().length()).sum(), echoed.length())));
    }

    /** M3-4-3: leak-after gives the instructions away after so many pieces, as one more piece, and completes. */
    @Test
    void itLeaksTheInstructionsAfterSoManyPieces() {
        Result result = generate("[fake pieces=5 leak-after=2]");

        assertThat(pieces).containsExactly("Piece 1 of 5. ", "Piece 2 of 5. ", INSTRUCTIONS);
        assertThat(result).isEqualTo(new Completed(new Usage("[fake pieces=5 leak-after=2]".length(),
                2 * "Piece 1 of 5. ".length() + INSTRUCTIONS.length())));
    }

    @Test
    void itCanLeakAtOnce() {
        generate("[fake leak-after=0]");

        assertThat(pieces).containsExactly(INSTRUCTIONS);
    }

    /** M3-4-3: say=contact answers one sentence with a telephone number and an email address, for the output check. */
    @Test
    void itSaysAContactWhenAsked() {
        Result result = generate("[fake say=contact] How do I reach you?");

        assertThat(pieces).containsExactly("You can call us on 03-1234-5678 or write to help@example.com. ");
        assertThat(pieces).containsExactly(FakeModel.CONTACT);
        assertThat(result).isEqualTo(new Completed(new Usage("[fake say=contact] How do I reach you?".length(),
                FakeModel.CONTACT.length())));
    }

    @Test
    void itSaysNothingElse() {
        Result result = generate("[fake say=hello]");

        assertThat(pieces).isEmpty();
        assertThat(((Failed) result).reason()).isEqualTo("The fake model says contact, not hello");
    }

    @Test
    void itEchoesNothingElse() {
        Result result = generate("[fake echo=prompt]");

        assertThat(pieces).isEmpty();
        assertThat(result).isInstanceOf(Failed.class);
        assertThat(((Failed) result).reason()).isEqualTo("The fake model echoes instructions or turns, not prompt");
    }

    @Test
    void anExceptionFromTheOutputReachesTheCaller() {
        assertThatThrownBy(() -> model.generate(request("Hello", LONG, NEVER), piece -> {
            throw new IllegalStateException("the caller could not keep it");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the caller could not keep it");
    }

    @Test
    void anInterruptEndsTheAnswerAndStaysSet() throws InterruptedException {
        AtomicReference<Result> result = new AtomicReference<>();
        AtomicBoolean stillInterrupted = new AtomicBoolean();
        Thread answer = Thread.ofVirtual().start(() -> {
            result.set(generate(request("[fake delay=5s]", LONG, NEVER)));
            stillInterrupted.set(Thread.currentThread().isInterrupted());
        });
        Thread.sleep(100);
        answer.interrupt();
        answer.join(Duration.ofSeconds(5));

        assertThat(result.get()).isInstanceOfSatisfying(Failed.class, failed -> assertThat(failed.reason()).contains("interrupted"));
        assertThat(stillInterrupted).isTrue();
    }
}

package aie.core.trust.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.trust.api.CheckMode;
import aie.core.trust.api.Checks;
import aie.core.trust.api.Checks.Release;
import aie.core.trust.api.Checks.Stop;
import aie.ports.ModerationPort;
import aie.ports.RedactionPort;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** M3-4-3: an answer held by the sentence or whole while it is checked, then let out masked, or stopped. */
class HeldOutputTest {

    /** Longer than 40 characters on purpose, with a line break where an answer might write a space. */
    private static final String INSTRUCTIONS = "You answer the customers of a business\non its behalf. Keep to what the business has told you.";

    /** What the moderation was asked, in order. */
    private final List<ModerationPort.Content> moderated = new CopyOnWriteArrayList<>();

    /** Moderation that notes each content and blocks a text holding "forbidden". */
    private final ModerationPort moderation = content -> {
        moderated.add(content);
        return content.text().contains("forbidden") ? new ModerationPort.Blocked("test_rule") : new ModerationPort.Allowed();
    };

    /** Masking that puts [n] for every run of digits. */
    private final RedactionPort redaction = text -> new RedactionPort.Redacted(text.replaceAll("[0-9]+", "[n]"),
            text.matches("(?s).*[0-9].*") ? Set.of(RedactionPort.Kind.PHONE) : Set.of());

    private Checks.Output held(CheckMode mode) {
        return new HeldOutput(mode, HeldOutput.stretches(INSTRUCTIONS), moderation, redaction);
    }

    @Test
    void aSentenceGoesOutOnceItHasEnded() {
        Checks.Output output = held(CheckMode.SENTENCE);

        assertThat(output.piece("Your parcel ")).isEqualTo(new Release(""));
        assertThat(output.piece("left today. It ")).isEqualTo(new Release("Your parcel left today. "));
        assertThat(output.piece("arrives soon")).isEqualTo(new Release(""));
        assertThat(output.end()).isEqualTo(new Release("It arrives soon"));
        assertThat(moderated).extracting(ModerationPort.Content::text).containsExactly("Your parcel left today. ", "It arrives soon");
        assertThat(moderated).extracting(ModerationPort.Content::direction).containsOnly(ModerationPort.Direction.OUTPUT);
    }

    /** Each sentence is checked on its own, so a piece that holds several lets them out together once all passed. */
    @Test
    void severalSentencesInOnePieceGoOutTogether() {
        Checks.Output output = held(CheckMode.SENTENCE);

        assertThat(output.piece("One. Two! Three? Four")).isEqualTo(new Release("One. Two! Three? "));
        assertThat(moderated).extracting(ModerationPort.Content::text).containsExactly("One. ", "Two! ", "Three? ");
    }

    /**
     * A sentence ends after 。！？!?, a line break, or a full stop with a space after it, and takes the spaces that
     * follow. A full stop at the end of what came so far may be a number's, so it waits.
     */
    @Test
    void whereASentenceEnds() {
        assertThat(HeldOutput.sentenceEnd("ご注文を受け付けました。次に")).isEqualTo(12);
        assertThat(HeldOutput.sentenceEnd("好的！还有")).isEqualTo(3);
        assertThat(HeldOutput.sentenceEnd("本当？はい")).isEqualTo(3);
        assertThat(HeldOutput.sentenceEnd("Yes!  Then")).isEqualTo(6);
        assertThat(HeldOutput.sentenceEnd("Why? ")).isEqualTo(5);
        assertThat(HeldOutput.sentenceEnd("A list\n- one")).isEqualTo(7);
        assertThat(HeldOutput.sentenceEnd("Done. Next")).isEqualTo(6);
        assertThat(HeldOutput.sentenceEnd("終了．次")).isEqualTo(3);
        assertThat(HeldOutput.sentenceEnd("It costs 3.50 now")).isZero();
        assertThat(HeldOutput.sentenceEnd("It costs 3.")).isZero();
        assertThat(HeldOutput.sentenceEnd("No end yet")).isZero();
        assertThat(HeldOutput.sentenceEnd("")).isZero();
    }

    /** A full stop that waited goes out with the next piece, once a space shows it ended a sentence. */
    @Test
    void aFullStopAtTheEndWaitsForTheNextPiece() {
        Checks.Output output = held(CheckMode.SENTENCE);

        assertThat(output.piece("It costs 3.")).isEqualTo(new Release(""));
        assertThat(output.piece("50 now. ")).isEqualTo(new Release("It costs [n].[n] now. "));
    }

    /** What goes out is masked; the moderation sees it as the model wrote it. */
    @Test
    void whatGoesOutIsMasked() {
        Checks.Output output = held(CheckMode.SENTENCE);

        assertThat(output.piece("Call 0312345678. ")).isEqualTo(new Release("Call [n]. "));
        assertThat(moderated).extracting(ModerationPort.Content::text).containsExactly("Call 0312345678. ");
    }

    /**
     * M3-4 decision 3: a sentence that repeats 40 characters of the instructions in a row stops the answer, white space of
     * any kind and length counted as one space, so the instructions' line break matches the answer's spaces. The
     * moderation is not asked then.
     */
    @Test
    void aSentenceThatGivesTheInstructionsAwayStopsTheAnswer() {
        assertThat(held(CheckMode.SENTENCE).piece("I was told: answer the customers of a business on its behalf. "))
                .isEqualTo(new Stop("prompt_leak"));
        assertThat(held(CheckMode.SENTENCE).piece("Well, the customers of a business \t on its behalf matter. "))
                .isEqualTo(new Stop("prompt_leak"));
        assertThat(moderated).isEmpty();
    }

    /** 40 characters in a row stop an answer; 39, or 40 that differ in one, do not. */
    @Test
    void fortyCharactersInARowAreALeak() {
        String forty = "customers of a business on its behalf. K";
        assertThat(forty).hasSize(HeldOutput.LEAK);

        assertThat(heldWhole(forty)).isEqualTo(new Stop("prompt_leak"));
        assertThat(heldWhole(forty.substring(1))).isEqualTo(new Release(forty.substring(1)));
        assertThat(heldWhole("x" + forty.substring(1))).isEqualTo(new Release("x" + forty.substring(1)));
    }

    /** The answer held whole, as one piece, and what the check makes of it at the end. */
    private Checks.Verdict heldWhole(String answer) {
        Checks.Output output = held(CheckMode.FULL);
        output.piece(answer);
        return output.end();
    }

    /** A sentence the moderation blocks stops the answer by the moderation's rule. */
    @Test
    void aSentenceTheModerationBlocksStopsTheAnswer() {
        Checks.Output output = held(CheckMode.SENTENCE);

        assertThat(output.piece("Fine. ")).isEqualTo(new Release("Fine. "));
        assertThat(output.piece("A forbidden thing. ")).isEqualTo(new Stop("test_rule"));
    }

    /** Once stopped, the answer lets nothing more out, whatever comes, and asks the ports nothing more. */
    @Test
    void aStoppedAnswerLetsNothingMoreOut() {
        Checks.Output output = held(CheckMode.SENTENCE);
        output.piece("A forbidden thing. ");
        moderated.clear();

        assertThat(output.piece("Fine. ")).isEqualTo(new Stop("test_rule"));
        assertThat(output.end()).isEqualTo(new Stop("test_rule"));
        assertThat(moderated).isEmpty();
    }

    /** A stop in a piece of several sentences lets none of them out, not even the ones before it. */
    @Test
    void aStopInAPieceLetsNoneOfItsSentencesOut() {
        assertThat(held(CheckMode.SENTENCE).piece("Fine. A forbidden thing. More. ")).isEqualTo(new Stop("test_rule"));
    }

    /** FULL holds the whole answer until the model has completed it, then checks it as one and lets it out masked. */
    @Test
    void fullHoldsTheWholeAnswer() {
        Checks.Output output = held(CheckMode.FULL);

        assertThat(output.piece("Your refund of 120 ")).isEqualTo(new Release(""));
        assertThat(output.piece("is approved. Thanks. ")).isEqualTo(new Release(""));
        assertThat(moderated).isEmpty();
        assertThat(output.end()).isEqualTo(new Release("Your refund of [n] is approved. Thanks. "));
        assertThat(moderated).extracting(ModerationPort.Content::text).containsExactly("Your refund of 120 is approved. Thanks. ");
    }

    /**
     * FULL checks the answer as one, so a leak split over two sentences shorter than 40 characters stops it, while SENTENCE
     * checks each sentence on its own, as decision 3 says, and lets them out.
     */
    @Test
    void fullCatchesALeakAcrossSentences() {
        Checks.Output whole = held(CheckMode.FULL);
        whole.piece("on its behalf.\n");
        whole.piece("Keep to what the business has told you.");
        Checks.Output bySentence = held(CheckMode.SENTENCE);

        assertThat(whole.end()).isEqualTo(new Stop("prompt_leak"));
        assertThat(bySentence.piece("on its behalf.\n")).isEqualTo(new Release("on its behalf.\n"));
        assertThat(bySentence.piece("Keep to what the business has told you.")).isEqualTo(new Release(""));
        assertThat(bySentence.end()).isEqualTo(new Release("Keep to what the business has told you."));
    }

    @Test
    void nothingHeldIsNothingToLetOut() {
        assertThat(held(CheckMode.SENTENCE).end()).isEqualTo(new Release(""));
        assertThat(held(CheckMode.FULL).end()).isEqualTo(new Release(""));
        assertThat(moderated).isEmpty();
    }

    @Test
    void anAnswerCheckedByItsInputAloneIsNotHeld() {
        assertThatThrownBy(() -> held(CheckMode.INPUT)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("An answer whose route checks its input alone is not held");
    }

    /** Instructions shorter than 40 characters have no stretch to repeat. */
    @Test
    void shortInstructionsGiveNothingAway() {
        assertThat(HeldOutput.stretches("Answer the test.")).isEmpty();
        assertThat(HeldOutput.stretches("a".repeat(41))).containsExactly("a".repeat(40));
        assertThat(HeldOutput.stretches("one\n\ntwo " + "x".repeat(40))).contains("one two " + "x".repeat(32));
    }
}

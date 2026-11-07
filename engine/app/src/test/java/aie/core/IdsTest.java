package aie.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class IdsTest {

    private static final long DAY = 86_400_000L;

    @Test
    void anIdIsThePrefixAndOnlyLettersAndDigits() {
        assertThat(Ids.next("conv")).matches("conv_[0-9A-HJKMNP-TV-Z]{26}");
    }

    @Test
    void theTimeIsEncodedInFull() {
        assertThat(Ids.next("x", 0)).startsWith("x_0000000000");
        assertThat(Ids.next("x", 1)).startsWith("x_0000000001");
        assertThat(Ids.next("x", 32)).startsWith("x_0000000010");
        assertThat(Ids.next("x", (1L << 48) - 1)).startsWith("x_7ZZZZZZZZZ");
    }

    @Test
    void laterIdsSortAfterEarlierOnes() {
        long now = System.currentTimeMillis();
        List<String> ids = Stream.of(now - DAY, now - 1, now, now + 1, now + DAY).map(time -> Ids.next("x", time)).toList();

        assertThat(ids).isSorted();
    }

    @Test
    void idsFromTheSameMomentDiffer() {
        Set<String> ids = new HashSet<>();
        IntStream.range(0, 10_000).forEach(i -> ids.add(Ids.next("x", 1_000)));

        assertThat(ids).hasSize(10_000);
    }

    @Test
    void theRandomPartUsesEveryDigit() {
        Set<Character> used = new HashSet<>();
        IntStream.range(0, 1_000).forEach(i -> Ids.next("x", 0).substring(12).chars().forEach(c -> used.add((char) c)));

        assertThat(used).hasSize(32);
    }
}

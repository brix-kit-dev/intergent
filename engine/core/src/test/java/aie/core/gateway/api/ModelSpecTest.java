package aie.core.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import aie.core.gateway.api.ModelSpec.Capability;
import aie.core.gateway.api.ModelSpec.Price;
import aie.core.trust.api.CheckMode;
import aie.core.trust.api.DataClass;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** M3-1: a model of the registry holds what its contract allows (blueprint 8.3), and the contract fits its classes (7.3). */
class ModelSpecTest {

    private static final Set<Capability> TEXT = Set.of(Capability.TEXT);
    private static final Price PRICE = new Price(new BigDecimal("3"), new BigDecimal("15"));

    private static ModelSpec model(int retentionDays, boolean training, DataClass maxDataClass) {
        return new ModelSpec("bedrock-jp/claude-sonnet-5-5", "anthropic", "claude", "jp", retentionDays, training, maxDataClass, TEXT, PRICE);
    }

    @Test
    void aModelKeepsWhatItsContractSays() {
        ModelSpec model = model(30, false, DataClass.PERSONAL);

        assertThat(model.retentionDays()).isEqualTo(30);
        assertThat(model.maxDataClass()).isEqualTo(DataClass.PERSONAL);
        assertThat(model.price()).isEqualTo(PRICE);
    }

    /** Public data may go to a model that trains on it; from internal up, it may not (blueprint 7.3). */
    @Test
    void fromInternalUpTheProviderMayNotTrainOnTheData() {
        assertThat(model(30, true, DataClass.PUBLIC).training()).isTrue();
        for (DataClass dataClass : EnumSet.range(DataClass.INTERNAL, DataClass.SENSITIVE)) {
            assertThatThrownBy(() -> model(0, true, dataClass))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The model bedrock-jp/claude-sonnet-5-5 takes " + dataClass.wireName()
                            + " data, so its provider may not train on it (blueprint 7.3)");
        }
    }

    /** Only a contract that keeps nothing may take sensitive data; personal data may be kept for a while. */
    @Test
    void sensitiveDataNeedsAContractThatKeepsNothing() {
        assertThat(model(0, false, DataClass.SENSITIVE).retentionDays()).isZero();
        assertThat(model(30, false, DataClass.PERSONAL).retentionDays()).isEqualTo(30);
        assertThatThrownBy(() -> model(1, false, DataClass.SENSITIVE))
                .hasMessage("The model bedrock-jp/claude-sonnet-5-5 takes sensitive data, so its contract must keep nothing (blueprint 7.3)");
    }

    @Test
    void everyFieldIsChecked() {
        assertThatThrownBy(() -> new ModelSpec("Claude", "anthropic", "claude", "jp", 0, false, DataClass.PUBLIC, TEXT, PRICE))
                .hasMessage("A model's id is a deployment and a model such as bedrock-jp/sonnet, in lower-case letters, digits and . _ -,"
                        + " not Claude");
        assertThatThrownBy(() -> new ModelSpec("a/b/c", "anthropic", "claude", "jp", 0, false, DataClass.PUBLIC, TEXT, PRICE))
                .hasMessageStartingWith("A model's id is");
        assertThatThrownBy(() -> new ModelSpec("m", "Anthropic", "claude", "jp", 0, false, DataClass.PUBLIC, TEXT, PRICE))
                .hasMessage("The model m needs its adapter in lower-case letters, digits and -, not Anthropic");
        assertThatThrownBy(() -> new ModelSpec("m", "anthropic", null, "jp", 0, false, DataClass.PUBLIC, TEXT, PRICE))
                .hasMessage("The model m needs its family in lower-case letters, digits and -, not null");
        assertThatThrownBy(() -> new ModelSpec("m", "anthropic", "claude", "", 0, false, DataClass.PUBLIC, TEXT, PRICE))
                .hasMessage("The model m needs its place of processing in lower-case letters, digits and -, not ");
        assertThatThrownBy(() -> new ModelSpec("m", "anthropic", "claude", "jp", -1, false, DataClass.PUBLIC, TEXT, PRICE))
                .hasMessage("The model m cannot keep data for less than no time");
        assertThatThrownBy(() -> new ModelSpec("m", "anthropic", "claude", "jp", 0, false, null, TEXT, PRICE))
                .hasMessage("The model m needs the highest data class it takes");
        assertThatThrownBy(() -> new ModelSpec("m", "anthropic", "claude", "jp", 0, false, DataClass.PUBLIC, Set.of(), PRICE))
                .hasMessage("The model m needs its capabilities, text at least for an answer");
        Set<Capability> withNull = new HashSet<>(TEXT);
        withNull.add(null);
        assertThatThrownBy(() -> new ModelSpec("m", "anthropic", "claude", "jp", 0, false, DataClass.PUBLIC, withNull, PRICE))
                .hasMessage("The model m needs its capabilities, text at least for an answer");
        assertThatThrownBy(() -> new ModelSpec("m", "anthropic", "claude", "jp", 0, false, DataClass.PUBLIC, TEXT, null))
                .hasMessage("The model m needs its price");
    }

    /** A model's capabilities do not change after it is made, whatever happens to the set it was given. */
    @Test
    void theCapabilitiesAreCopied() {
        Set<Capability> given = new HashSet<>(Set.of(Capability.TEXT, Capability.TOOLS));
        ModelSpec model = new ModelSpec("m", "anthropic", "claude", "jp", 0, false, DataClass.PUBLIC, given, PRICE);
        given.clear();

        assertThat(model.capabilities()).containsExactlyInAnyOrder(Capability.TEXT, Capability.TOOLS);
    }

    @Test
    void aPriceIsZeroOrMore() {
        assertThat(new Price(BigDecimal.ZERO, BigDecimal.ZERO).input()).isZero();
        assertThatThrownBy(() -> new Price(new BigDecimal("-0.01"), BigDecimal.ONE))
                .hasMessage("A price is a cost per million tokens in and out, zero or more, not -0.01 and 1");
        assertThatThrownBy(() -> new Price(BigDecimal.ONE, new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Price(null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Price(BigDecimal.ONE, null)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * M3-6-1: what a call cost is the tokens in and out at the price of a million, exactly: no rounding, however small
     * or large, so that a month of calls sums to what they cost.
     */
    @Test
    void aCallCostsItsTokensAtTheirPriceExactly() {
        Price price = new Price(new BigDecimal("3"), new BigDecimal("15"));

        assertThat(price.cost(1234, 567)).isEqualByComparingTo("0.012207");
        assertThat(price.cost(1, 0)).isEqualByComparingTo("0.000003");
        assertThat(new Price(new BigDecimal("0.075"), new BigDecimal("0.3")).cost(1, 1)).isEqualByComparingTo("0.000000375");
        assertThat(price.cost(0, 0)).isEqualByComparingTo("0");
        assertThat(price.cost(Long.MAX_VALUE, Long.MAX_VALUE)).isEqualByComparingTo(
                new BigDecimal(Long.MAX_VALUE).multiply(new BigDecimal("18")).movePointLeft(6));
        assertThat(Price.FREE.cost(1_000_000, 1_000_000)).isEqualByComparingTo("0");
        assertThatThrownBy(() -> price.cost(-1, 0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A call uses 0 tokens or more, not -1 and 0");
        assertThatThrownBy(() -> price.cost(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    /** The classes compare by how sensitive they are, which routing needs (M3-3). */
    @Test
    void theDataClassesGoFromPublicToSensitive() {
        assertThat(DataClass.values()).containsExactly(DataClass.PUBLIC, DataClass.INTERNAL, DataClass.PERSONAL, DataClass.SENSITIVE);
    }

    @ParameterizedTest
    @EnumSource(DataClass.class)
    void aDataClassHasItsWireName(DataClass dataClass) {
        assertThat(DataClass.fromWireName(dataClass.wireName())).contains(dataClass);
        assertThat(dataClass.wireName()).isEqualTo(dataClass.name().toLowerCase(java.util.Locale.ROOT));
    }

    @ParameterizedTest
    @EnumSource(CheckMode.class)
    void aCheckModeHasItsWireName(CheckMode mode) {
        assertThat(CheckMode.fromWireName(mode.wireName())).contains(mode);
    }

    @ParameterizedTest
    @EnumSource(Capability.class)
    void aCapabilityHasItsWireName(Capability capability) {
        assertThat(Capability.fromWireName(capability.wireName())).contains(capability);
    }

    @Test
    void anUnknownNameIsNone() {
        assertThat(DataClass.fromWireName("secret")).isEqualTo(Optional.empty());
        assertThat(DataClass.fromWireName("PUBLIC")).isEmpty();
        assertThat(CheckMode.fromWireName("words")).isEmpty();
        assertThat(Capability.fromWireName("telepathy")).isEmpty();
        assertThat(CheckMode.SENTENCE.wireName()).isEqualTo("sentence");
        assertThat(Capability.STRUCTURED_OUTPUT.wireName()).isEqualTo("structured_output");
    }
}

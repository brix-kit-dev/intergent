package aie.core.gateway.api;

import aie.core.trust.api.DataClass;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A model of the registry and what its contract allows (blueprint 8.3), so that routing holds to the contract in code
 * rather than by agreement. The ID names a deployment and a model, because the same model reached another way may
 * process the data elsewhere or keep it longer. The contract must allow the highest class the model takes (blueprint
 * 7.3): from internal up the provider may not train on the data, and sensitive data needs a contract that keeps
 * nothing.
 *
 * @param id the deployment and the model, such as bedrock-jp/sonnet: the name routes use
 * @param adapter the adapter that calls the model, such as anthropic
 * @param family the prompt templates the model takes, such as claude
 * @param processedIn where the deployment processes the data, such as jp: the region pack's processing_region
 * @param retentionDays how many days the provider keeps what it is sent; 0 when the contract keeps nothing
 * @param training whether the provider may train on what it is sent
 * @param maxDataClass the highest class of data the model takes
 * @param capabilities what the model can do
 * @param price what a million tokens cost
 */
public record ModelSpec(String id, String adapter, String family, String processedIn, int retentionDays,
        boolean training, DataClass maxDataClass, Set<Capability> capabilities, Price price) {

    /** A deployment and a model, or a model alone: lower-case letters, digits and . _ - with at most one /. */
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)?");
    /** An adapter, a family or a place of processing: lower-case letters, digits and -. */
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");

    /** @throws IllegalArgumentException naming the model and what is wrong */
    public ModelSpec {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("A model's id is a deployment and a model such as bedrock-jp/sonnet, in lower-case letters,"
                    + " digits and . _ -, not " + id);
        }
        name(id, "adapter", adapter);
        name(id, "family", family);
        name(id, "place of processing", processedIn);
        if (retentionDays < 0) {
            throw new IllegalArgumentException("The model " + id + " cannot keep data for less than no time");
        }
        if (maxDataClass == null) {
            throw new IllegalArgumentException("The model " + id + " needs the highest data class it takes");
        }
        if (capabilities == null || capabilities.isEmpty() || capabilities.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("The model " + id + " needs its capabilities, text at least for an answer");
        }
        Objects.requireNonNull(price, () -> "The model " + id + " needs its price");
        if (maxDataClass.compareTo(DataClass.INTERNAL) >= 0 && training) {
            throw new IllegalArgumentException("The model " + id + " takes " + maxDataClass.wireName()
                    + " data, so its provider may not train on it (blueprint 7.3)");
        }
        if (maxDataClass == DataClass.SENSITIVE && retentionDays != 0) {
            throw new IllegalArgumentException("The model " + id
                    + " takes sensitive data, so its contract must keep nothing (blueprint 7.3)");
        }
        capabilities = Set.copyOf(capabilities);
    }

    private static void name(String id, String field, String value) {
        if (value == null || !NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("The model " + id + " needs its " + field
                    + " in lower-case letters, digits and -, not " + value);
        }
    }

    /** What a model can do (blueprint 8.3). */
    public enum Capability {
        TEXT,
        VISION,
        TOOLS,
        STRUCTURED_OUTPUT,
        PROMPT_CACHE;

        /** The name in a region pack. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The capability with this name, or empty. */
        public static Optional<Capability> fromWireName(String name) {
            for (Capability capability : values()) {
                if (capability.wireName().equals(name)) {
                    return Optional.of(capability);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * What a million tokens cost, in US dollars, as the providers list their prices. Metering counts from it (M3-6).
     *
     * @param input a million tokens sent to the model
     * @param output a million tokens the model writes
     */
    public record Price(BigDecimal input, BigDecimal output) {

        /** Nothing for any number of tokens, as the engine's own stand-in model costs. */
        public static final Price FREE = new Price(BigDecimal.ZERO, BigDecimal.ZERO);

        /** @throws IllegalArgumentException for a missing or negative price */
        public Price {
            if (input == null || output == null || input.signum() < 0 || output.signum() < 0) {
                throw new IllegalArgumentException("A price is a cost per million tokens in and out, zero or more, not "
                        + input + " and " + output);
            }
        }

        /**
         * What a call that used these tokens cost, in US dollars, exactly: no rounding, so that the sum of many calls is
         * the sum of their costs (M3-6-1).
         *
         * @throws IllegalArgumentException for a count of tokens below 0
         */
        public BigDecimal cost(long inputTokens, long outputTokens) {
            if (inputTokens < 0 || outputTokens < 0) {
                throw new IllegalArgumentException("A call uses 0 tokens or more, not " + inputTokens + " and " + outputTokens);
            }
            return input.multiply(BigDecimal.valueOf(inputTokens)).add(output.multiply(BigDecimal.valueOf(outputTokens)))
                    .movePointLeft(6);
        }
    }
}

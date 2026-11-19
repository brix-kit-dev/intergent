package aie.core.trust.api;

import java.util.Locale;
import java.util.Optional;

/**
 * How sensitive some data is (blueprint 7.3), lowest first, so that a higher class compares as greater. A model takes
 * data up to its highest class, and a call of a higher class finds no model and fails rather than going to a lesser
 * one. The trust layer owns the classes, so that the gateway can use them and the trust layer never needs the gateway.
 */
public enum DataClass {
    /** Published material, such as a product page: any approved model. */
    PUBLIC,
    /** A company's own documents: models that do not train on them. */
    INTERNAL,
    /** Orders and names: models that do not train on them, under a data processing agreement. */
    PERSONAL,
    /** Medical records and the like: models whose contract keeps nothing, on the approved list. */
    SENSITIVE;

    /** The name on the wire, in the contract and in a region pack. */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The class with this wire name, or empty. */
    public static Optional<DataClass> fromWireName(String name) {
        for (DataClass dataClass : values()) {
            if (dataClass.wireName().equals(name)) {
                return Optional.of(dataClass);
            }
        }
        return Optional.empty();
    }
}

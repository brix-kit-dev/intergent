package aie.core.tenancy.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;

/**
 * Checks a site key and the origin of the browser's request: the system path of blueprint 7.6. It runs before the
 * tenant is known, so it returns only the application the key belongs to, or why there is none.
 */
public interface SiteKeyLookup {

    /**
     * What the key and the origin give. {@code origin} is the request's Origin header as sent, or null without one. A
     * malformed key never reaches the database.
     */
    Checked check(String siteKey, String origin);

    /** The answer of a check. */
    sealed interface Checked {

        /** The key is active and lists the origin. The owner's key ID is the site key's ID. */
        record Allowed(Owner owner) implements Checked {
        }

        /** The key is active but does not list this origin, or the request has none. */
        record OtherOrigin() implements Checked {
        }

        /** The key is malformed, was never issued, or was revoked. All three look the same. */
        record Unknown() implements Checked {
        }
    }
}

package aie.core.trust.api;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Reading the audit (blueprint 9.4, M3-5-2): one tenant's entries, oldest first, for the admin role alone (M3 decision
 * 6). The gateway, which adds the entries, gets {@link Audit} and never this.
 */
public interface AuditTrail {

    /**
     * Hands each entry of the tenant added from {@code from} on and before {@code to} to {@code each}, oldest first: by
     * the time the database added it, then by its ID. The entries are read a page at a time, each page in a short
     * transaction of its own, and a page is handed over only once its transaction has ended, so {@code each} may take
     * its time. Another tenant's entries never come.
     *
     * @param from the earliest time an entry may have, or null for no earliest
     * @param to the time every entry comes before, or null for no latest
     * @throws IllegalArgumentException for a tenant that is missing, or a {@code from} that is not before {@code to}
     */
    void read(String tenantId, Instant from, Instant to, Consumer<Recorded> each);

    /**
     * An entry as the audit keeps it.
     *
     * @param recordId its ID, such as {@code aud_01J...}
     * @param createdAt when the database added it
     * @param entry what it tells
     */
    record Recorded(String recordId, Instant createdAt, Audit.Entry entry) {
    }
}

package aie.core.tenancy.internal;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.tenancy.api.SiteKeyLookup;
import aie.core.tenancy.api.SiteKeys;
import aie.ports.DatabasePort;
import java.util.List;

/**
 * {@link SiteKeyLookup} on the database. A system transaction holds no tenant, so it reads no business table, and the
 * site key table is reached only through the system function that searches it by hash.
 */
public final class DatabaseSiteKeyLookup implements SiteKeyLookup {

    private static final String FIND =
            "SELECT site_key_id, tenant_id, application_id, origin_allowed FROM tenancy_find_site_key(?, ?)";

    private final DatabasePort database;

    public DatabaseSiteKeyLookup(DatabasePort database) {
        this.database = database;
    }

    /**
     * The origin is compared in the form a browser sends it, so one it could not have sent, such as {@code null} from
     * a sandboxed frame, matches nothing. The hash is unique, so at most one row comes back.
     */
    @Override
    public Checked check(String siteKey, String origin) {
        if (siteKey == null || SiteKeys.parse(siteKey).isEmpty()) {
            return new Checked.Unknown();
        }
        String requestOrigin = SiteKeys.origin(origin).orElse(null);
        List<Checked> found = database.inSystemTx(tx -> tx.query(FIND, row -> row.flag("origin_allowed")
                ? new Checked.Allowed(new Owner(row.string("site_key_id"), row.string("tenant_id"), row.string("application_id")))
                : new Checked.OtherOrigin(), SiteKeys.hash(siteKey), requestOrigin));
        return found.isEmpty() ? new Checked.Unknown() : found.getFirst();
    }
}

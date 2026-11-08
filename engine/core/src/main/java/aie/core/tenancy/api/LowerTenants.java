package aie.core.tenancy.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;

/**
 * Blueprint 6.5: a platform host's key acts for one of its lower tenants with On-Behalf-Of. The request then runs as
 * that tenant's application of the key's environment, the way a Stripe Connect platform's test key works on a
 * connected account's test data. It never guesses: without exactly one such application, the key may not act there.
 */
public interface LowerTenants {

    /** Whom the caller may act as in that tenant, for a key of this environment. */
    Acting actFor(Owner caller, String environment, String lowerTenantId);

    /** The answer, so that a refusal can say which rule failed. */
    sealed interface Acting {

        /** The caller's key, working as the lower tenant's application. */
        record As(Owner owner) implements Acting {
        }

        /** Not a lower tenant of the caller's tenant here: unknown, unrelated, the caller's own, or further down. */
        record NotALowerTenant() implements Acting {
        }

        /** A lower tenant, but without exactly one application of the key's environment. */
        record NoSingleApplication() implements Acting {
        }
    }
}

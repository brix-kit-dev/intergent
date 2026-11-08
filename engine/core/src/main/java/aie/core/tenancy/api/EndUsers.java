package aie.core.tenancy.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;

/**
 * The end users of blueprint 7.1: the host's users as the engine knows them. The engine keeps no accounts, only the
 * host's own ID, and gives each end user a random ID of its own, which is all other records refer to.
 */
public interface EndUsers {

    /**
     * The end user behind the host's ID in the caller's application, made the first time it is seen. The same ID
     * always gives the same end user, also when two requests bring it at the same moment.
     */
    String resolve(Owner caller, String externalId);
}

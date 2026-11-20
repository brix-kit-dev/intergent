package aie.core.tenancy.api;

import aie.core.tenancy.api.ApiKeyLookup.Owner;
import aie.core.trust.api.DataClass;

/** What the rest of the engine reads of an application. */
public interface Applications {

    /**
     * The data class the caller's application holds its calls to at the least (blueprint 7.3): a call can raise it, never
     * go below it. It is read for each call, so a change applies to the next one.
     *
     * @throws IllegalStateException when the caller's application does not exist, which an authenticated caller rules out
     */
    DataClass defaultDataClass(Owner caller);
}

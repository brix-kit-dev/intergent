package aie.app;

import java.util.Set;

/**
 * The lists of blueprint 7.6. A table outside {@link #TABLES} must be a tenant table, and a SECURITY DEFINER function
 * outside {@link #FUNCTIONS} fails the catalog check. Flyway's history tables are system tables too, but their names
 * follow from the modules, so they are not listed here.
 */
final class SystemObjects {

    static final Set<String> TABLES = Set.of();
    static final Set<String> FUNCTIONS = Set.of("tenancy_find_api_key", "tenancy_find_lower_applications");

    private SystemObjects() {
    }
}

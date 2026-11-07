package aie.adapters.storepg;

import org.postgresql.ds.PGSimpleDataSource;

/** Connections for one fixed role. The address only picks the server, so a wrong setting cannot change who connects. */
final class PgConnections {

    /** api, worker and admin all run as this role (blueprint 7.6). */
    static final String RUNTIME_ROLE = "engine_app";

    private PgConnections() {
    }

    static PGSimpleDataSource as(String role, String url, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        try {
            dataSource.setUrl(url);
        } catch (IllegalArgumentException e) {
            // The driver's message repeats the address, and an address can carry a password.
            throw new IllegalArgumentException("The database address is not a PostgreSQL JDBC URL");
        }
        dataSource.setUser(role);
        dataSource.setPassword(password);
        return dataSource;
    }
}

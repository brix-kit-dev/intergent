package aie.adapters.storepg;

import aie.ports.DatabaseException;
import aie.ports.Row;
import aie.ports.Tx;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Runs SQL in the transaction bound to the current thread.
 *
 * <p>A statement on any other thread, or after the call has ended, would run outside the transaction with no tenant
 * context, so both are refused.
 */
final class PgTx implements Tx, AutoCloseable {

    private final JdbcClient jdbc;
    private final Thread owner = Thread.currentThread();
    private volatile boolean open = true;

    PgTx(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int update(String sql, Object... arguments) {
        checkUsable();
        try {
            return jdbc.sql(sql).params(bind(arguments)).update();
        } catch (DataAccessException e) {
            throw new DatabaseException("The statement failed", e);
        }
    }

    @Override
    public <T> List<T> query(String sql, Function<Row, T> mapper, Object... arguments) {
        checkUsable();
        try {
            return jdbc.sql(sql).params(bind(arguments)).query((result, number) -> mapper.apply(new ResultRow(result))).list();
        } catch (DataAccessException e) {
            throw new DatabaseException("The query failed", e);
        }
    }

    @Override
    public void close() {
        open = false;
    }

    private void checkUsable() {
        if (!open) {
            throw new IllegalStateException("This transaction has ended");
        }
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("This transaction belongs to another thread");
        }
    }

    private static List<Object> bind(Object[] arguments) {
        return Arrays.stream(arguments)
                .map(argument -> argument instanceof Instant time ? OffsetDateTime.ofInstant(time, ZoneOffset.UTC) : argument)
                .toList();
    }

    private record ResultRow(ResultSet result) implements Row {

        @Override
        public String string(String column) {
            return read(() -> result.getString(column));
        }

        @Override
        public Instant time(String column) {
            OffsetDateTime time = read(() -> result.getObject(column, OffsetDateTime.class));
            return time == null ? null : time.toInstant();
        }

        @Override
        public long number(String column) {
            return required(column, read(() -> result.getObject(column, Long.class)));
        }

        @Override
        public boolean flag(String column) {
            return required(column, read(() -> result.getObject(column, Boolean.class)));
        }

        private static <V> V required(String column, V value) {
            if (value == null) {
                throw new IllegalStateException("Column " + column + " is null");
            }
            return value;
        }

        private static <V> V read(ColumnRead<V> read) {
            try {
                return read.value();
            } catch (SQLException e) {
                throw new DatabaseException("Could not read the column", e);
            }
        }
    }

    @FunctionalInterface
    private interface ColumnRead<V> {
        V value() throws SQLException;
    }
}

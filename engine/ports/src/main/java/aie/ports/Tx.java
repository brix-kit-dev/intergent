package aie.ports;

import java.util.List;
import java.util.function.Function;

/**
 * An open transaction. It belongs to the thread that opened it and stops working when that call ends.
 *
 * <p>SQL uses {@code ?} for each argument. Arguments may be strings, numbers, booleans and instants.
 */
public interface Tx {

    /** Runs a statement and returns the number of rows it changed. */
    int update(String sql, Object... arguments);

    /** Runs a query and maps each row. */
    <T> List<T> query(String sql, Function<Row, T> mapper, Object... arguments);
}

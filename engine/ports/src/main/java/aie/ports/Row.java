package aie.ports;

import java.time.Instant;

/** One row of a query result, valid only while its mapper runs. */
public interface Row {

    /** Returns the text in this column, or null. */
    String string(String column);

    /** Returns the time in this column, or null. */
    Instant time(String column);

    /** Returns the whole number in this column, and fails on null. */
    long number(String column);

    /** Returns the boolean in this column, and fails on null. */
    boolean flag(String column);
}

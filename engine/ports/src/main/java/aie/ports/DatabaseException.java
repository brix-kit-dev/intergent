package aie.ports;

/** A database call failed. Any open transaction has been or will be rolled back. */
public class DatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}

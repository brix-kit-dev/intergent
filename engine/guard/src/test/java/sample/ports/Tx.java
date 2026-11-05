package sample.ports;

public interface Tx {

    int update(String sql, Object... arguments);

    interface Savepoint {
    }
}

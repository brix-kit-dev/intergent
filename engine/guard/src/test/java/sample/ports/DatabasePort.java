package sample.ports;

import java.util.function.Function;

public interface DatabasePort {

    <T> T inTenantTx(String tenantId, Function<Tx, T> work);

    <T> T inSystemTx(Function<Tx, T> work);
}

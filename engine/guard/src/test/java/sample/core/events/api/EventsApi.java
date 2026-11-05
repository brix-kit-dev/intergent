package sample.core.events.api;

import sample.ports.Tx;

public interface EventsApi {

    void publish(Tx tx, String type);
}

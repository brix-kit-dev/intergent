package sample.core.events.internal;

import sample.core.events.api.EventsApi;
import sample.ports.DatabasePort;
import sample.ports.Tx;
import sample.ports.WebhookPort;

public class EventsImpl implements EventsApi {

    DatabasePort database;
    WebhookPort webhook;

    @Override
    public void publish(Tx tx, String type) {
        tx.update("insert into events_outbox(type) values (?)", type);
    }

    int pending() {
        return database.inSystemTx(tx -> tx.update("select events_claim()"));
    }
}

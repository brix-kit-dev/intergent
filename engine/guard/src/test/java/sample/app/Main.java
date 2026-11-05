package sample.app;

import javax.sql.DataSource;
import sample.adapters.fake.FakeModel;
import sample.adapters.hosthttp.HostHttp;
import sample.adapters.storepg.PgDatabase;
import sample.core.tenancy.internal.TenancyImpl;
import sample.http.SessionsController;
import sample.modules.conversation.internal.ConversationImpl;
import sample.ports.ModelPort;

/** The startup entry wires everything, so it may see adapters, internals and the data source. */
public class Main {

    FakeModel fake;
    PgDatabase database;
    HostHttp host;
    DataSource source;
    ModelPort model;
    TenancyImpl tenancy;
    ConversationImpl conversations;
    SessionsController sessions;
}

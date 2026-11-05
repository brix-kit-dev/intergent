package sample.modules.conversation.internal;

import sample.core.events.api.EventsApi;
import sample.core.gateway.api.GatewayApi;
import sample.modules.conversation.api.ConversationApi;
import sample.modules.knowledge.api.KnowledgeApi;
import sample.ports.DatabasePort;

public class ConversationImpl implements ConversationApi {

    GatewayApi gateway;
    KnowledgeApi knowledge;
    EventsApi events;
    DatabasePort database;

    void add(String tenantId) {
        database.inTenantTx(tenantId, tx -> {
            events.publish(tx, "conversation.message");
            return tx.update("insert into conversation_messages default values");
        });
    }
}

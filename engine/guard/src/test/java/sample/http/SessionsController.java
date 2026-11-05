package sample.http;

import sample.core.metering.api.MeteringApi;
import sample.core.tenancy.api.TenancyApi;
import sample.modules.conversation.api.ConversationApi;
import sample.modules.conversation.api.model.Message;

public class SessionsController {

    ConversationApi conversations;
    TenancyApi tenancy;
    MeteringApi metering;
    Message latest;
}

package sample.sdk;

import aie.guard.Violates;

@Violates("A-18-outside")
class SdkUsesModule {

    sample.modules.conversation.api.ConversationApi field;
}

@Violates("A-18-sdk")
class SdkUsesProduct {

    sample.products.support.SupportFlow field;
}

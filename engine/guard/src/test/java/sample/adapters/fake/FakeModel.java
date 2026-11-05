package sample.adapters.fake;

import com.anthropic.client.AnthropicClient;
import org.springframework.ai.chat.model.ChatModel;
import sample.ports.ModelPort;

public class FakeModel implements ModelPort {

    AnthropicClient vendor;
    ChatModel framework;

    @Override
    public Reply call(Request request) {
        return new Reply(request.prompt());
    }
}

package sample.ports;

import java.net.URI;

public interface WebhookPort {

    void deliver(URI target, String body);
}

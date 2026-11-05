package sample.adapters.hosthttp;

import java.net.URI;
import java.net.http.HttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import sample.ports.WebhookPort;

public class HostHttp implements WebhookPort {

    HttpClient client;
    CloseableHttpClient pooled;

    @Override
    public void deliver(URI target, String body) {
    }
}

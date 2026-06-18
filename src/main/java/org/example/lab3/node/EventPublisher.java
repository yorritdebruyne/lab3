package org.example.lab3.node;

import org.example.lab3.model.SystemEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * EventPublisher lets a NODE report what it did to the naming server's live
 * activity feed. The naming server re-broadcasts it to all browsers
 * over SSE.
 *
 * Best-effort and non-blocking: the POST runs on a daemon thread and any failure
 * is swallowed, so the feed can never slow down or break the node's real work.
 */
@Service
@Profile("node")
public class EventPublisher {

    @Value("${namingserver.url}")
    private String namingServerUrl;

    private final NodeState state;
    private final RestTemplate restTemplate = new RestTemplate();

    public EventPublisher(NodeState state) {
        this.state = state;
    }

    public void publish(String type, String message) {
        final SystemEvent event = new SystemEvent(type, message, state.getName());
        Thread t = new Thread(() -> {
            try {
                restTemplate.postForObject(namingServerUrl + "/api/events", event, Void.class);
            } catch (Exception ignored) {
                // feed is best-effort — never let it affect node behaviour
            }
        }, "event-publish");
        t.setDaemon(true);
        t.start();
    }
}

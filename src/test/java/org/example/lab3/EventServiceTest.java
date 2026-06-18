package org.example.lab3;

import org.example.lab3.model.SystemEvent;
import org.example.lab3.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.*;

class EventServiceTest {

    @Test
    void publish_storesEventInRecentBuffer() {
        EventService s = new EventService();
        s.publish("JOIN", "nodeA joined the ring", "naming-server");

        assertEquals(1, s.getRecent().size());
        assertEquals("JOIN", s.getRecent().get(0).getType());
        assertEquals("naming-server", s.getRecent().get(0).getSource());
        assertTrue(s.getRecent().get(0).getTimestamp() > 0, "timestamp should be set on publish");
    }

    @Test
    void recentBuffer_isCappedAtFifty() {
        EventService s = new EventService();
        for (int i = 0; i < 60; i++) s.publish(new SystemEvent("INFO", "e" + i, "x"));

        assertEquals(50, s.getRecent().size(), "buffer must not grow unbounded");
        assertEquals("e10", s.getRecent().get(0).getMessage(), "oldest 10 events should be dropped");
        assertEquals("e59", s.getRecent().get(49).getMessage());
    }

    @Test
    void subscribe_returnsEmitterAndReplaysHistory() {
        EventService s = new EventService();
        s.publish("JOIN", "nodeA joined", "naming-server");

        SseEmitter emitter = s.subscribe();
        assertNotNull(emitter, "subscribe must return an open SSE emitter");
    }
}

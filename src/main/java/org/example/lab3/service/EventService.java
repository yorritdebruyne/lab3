package org.example.lab3.service;

import org.example.lab3.model.SystemEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * EventService is the fan-out hub for the live activity feed.
 *
 * Browsers open a Server-Sent Events stream (GET /api/events) and are kept in
 * {@code emitters}. Anything that calls {@link #publish} — the naming server
 * itself, or a node POSTing to /api/events — is broadcast to every open browser
 * immediately.
 *
 * A small ring buffer of the most recent events is replayed to each new
 * subscriber, so a freshly-opened GUI shows recent history instead of a blank
 * feed until the next event happens.
 *
 * Only exists on the naming server (the single public endpoint via Nginx).
 */
@Service
@Profile("naming-server")
public class EventService {

    private static final int MAX_RECENT = 50;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Deque<SystemEvent> recent = new ArrayDeque<>();

    /** Opens a new SSE stream and replays recent history onto it. */
    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // 0 = no server-side timeout
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()    -> emitters.remove(emitter));
        emitter.onError(e       -> emitters.remove(emitter));
        emitters.add(emitter);

        List<SystemEvent> history;
        synchronized (recent) { history = new ArrayList<>(recent); }
        for (SystemEvent e : history) {
            if (!send(emitter, e)) break; // emitter already dead → stop replaying
        }
        return emitter;
    }

    /** Records an event and pushes it to every connected browser. */
    public void publish(SystemEvent event) {
        if (event.getTimestamp() == 0) event.setTimestamp(System.currentTimeMillis());

        synchronized (recent) {
            recent.addLast(event);
            while (recent.size() > MAX_RECENT) recent.removeFirst();
        }
        for (SseEmitter emitter : emitters) {
            send(emitter, event);
        }
    }

    /** Convenience for naming-server-internal events. */
    public void publish(String type, String message, String source) {
        publish(new SystemEvent(type, message, source));
    }

    /** Snapshot of recent events (used by tests / debugging). */
    public List<SystemEvent> getRecent() {
        synchronized (recent) { return new ArrayList<>(recent); }
    }

    private boolean send(SseEmitter emitter, SystemEvent event) {
        try {
            emitter.send(SseEmitter.event().name("system").data(event));
            return true;
        } catch (Exception ex) {
            emitters.remove(emitter); // browser closed the tab / connection dropped
            return false;
        }
    }
}

package org.example.lab3.controller;

import org.example.lab3.model.SystemEvent;
import org.example.lab3.service.EventService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * EventController exposes the live activity feed (Tier 2).
 *
 *   GET  /api/events  → Server-Sent Events stream the browser subscribes to.
 *   POST /api/events  → publish an event (used by NODES to report what they did,
 *                       e.g. a file hand-off during join redistribution).
 *
 * The naming server publishes its own events (node join / leave) directly via
 * EventService, so the browser sees both server-side and node-side activity in
 * one stream.
 */
@RestController
@RequestMapping("/api/events")
@Profile("naming-server")
@CrossOrigin(origins = "*")
public class EventController {

    private final EventService events;

    public EventController(EventService events) {
        this.events = events;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return events.subscribe();
    }

    @PostMapping
    public ResponseEntity<Void> publish(@RequestBody SystemEvent event) {
        events.publish(event);
        return ResponseEntity.ok().build();
    }
}

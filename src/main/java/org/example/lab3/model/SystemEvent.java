package org.example.lab3.model;

/**
 * A single activity event shown in the GUI's live feed.
 *
 * Produced by the naming server (node join / leave) and by nodes (e.g. a file
 * hand-off during join redistribution), then streamed to the browser over SSE.
 *
 * Plain POJO so Jackson can (de)serialise it in both directions:
 *   - nodes POST it to the naming server,
 *   - the naming server sends it to browsers as an SSE data payload.
 */
public class SystemEvent {

    private String type;     // JOIN, LEAVE, REDISTRIBUTE, FAILURE, INFO
    private String message;  // human-readable line shown in the feed
    private String source;   // node name, or "naming-server"
    private long   timestamp; // epoch millis (set on creation if omitted)

    public SystemEvent() {}

    public SystemEvent(String type, String message, String source) {
        this.type = type;
        this.message = message;
        this.source = source;
        this.timestamp = System.currentTimeMillis();
    }

    public String getType()              { return type; }
    public void setType(String type)     { this.type = type; }

    public String getMessage()           { return message; }
    public void setMessage(String m)     { this.message = m; }

    public String getSource()            { return source; }
    public void setSource(String s)      { this.source = s; }

    public long getTimestamp()           { return timestamp; }
    public void setTimestamp(long t)     { this.timestamp = t; }
}

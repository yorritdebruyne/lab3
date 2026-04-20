package org.example.lab3.model;

/**
 * Response object returned by GET /api/nodes/neighbours/{id}.
 *
 * Contains the IDs (hash ring positions) of the previous and next node
 * relative to the requested node ID.
 *
 * This is used exclusively during failure recovery so that surviving nodes
 * can update their own prev/next pointers.
 */
public class NeighbourResponse {
    private int prevId;
    private int nextId;

    public NeighbourResponse(int prevId, int nextId) {
        this.prevId = prevId;
        this.nextId = nextId;
    }

    public int getPrevId() { return prevId; }
    public int getNextId() { return nextId; }
}
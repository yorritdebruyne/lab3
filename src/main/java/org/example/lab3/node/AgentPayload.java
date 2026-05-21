package org.example.lab3.node;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentPayload is the JSON body that travels between nodes via REST.
 *
 * Instead of serializing a Java object to bytes (the old Serializable approach),
 * we serialize the agent's entire state to JSON and POST it to the next node.
 * The receiving node reconstructs everything it needs from this payload.
 *
 * === Termination for FAILURE agents ===
 *
 * Each node appends its own ID to visitedNodeIds before forwarding.
 * When the payload arrives at a node whose ID is already in visitedNodeIds,
 * the ring has been fully traversed — AgentController terminates without
 * forwarding further.
 *
 * This approach is more robust than comparing a startNodeId integer because
 * List<Integer> serializes unambiguously as a JSON array, whereas primitive
 * int fields can be lost if Jackson's serialization config excludes defaults.
 *
 * Fields:
 *   type          — which agent this is: "SYNC" or "FAILURE"
 *   failingNodeId — (FAILURE only) ring ID of the node that crashed
 *   visitedNodeIds — (FAILURE only) IDs of nodes already visited, in order
 *   fileList      — the agent's accumulated list of FileEntries (SYNC only)
 *   hopCount      — safety counter; agent terminates if this exceeds MAX_HOPS
 */
public class AgentPayload {

    // Tells the receiving node which agent type this is
    private String type;

    // FAILURE agent: ID of the node that crashed
    private int failingNodeId = -1;

    // FAILURE agent: ordered list of node IDs that have already processed this agent.
    // The agent terminates when it arrives at a node already in this list.
    private List<Integer> visitedNodeIds = new ArrayList<>();

    // The file list this agent carries (SYNC only).
    private List<FileEntry> fileList = new ArrayList<>();

    // Safety counter: incremented by each forwarding hop.
    // If this exceeds MAX_HOPS the agent terminates as a hard safety net.
    private int hopCount = 0;
    public static final int MAX_HOPS = 50;

    // Empty constructor required by Jackson for JSON deserialization
    public AgentPayload() {}

    // Factory method for a SYNC agent payload
    static public AgentPayload forSync(List<FileEntry> fileList) {
        AgentPayload payload = new AgentPayload();
        payload.type = "SYNC";
        payload.fileList = fileList;
        return payload;
    }

    // Factory method for a FAILURE agent payload
    static public AgentPayload forFailure(int failingNodeId) {
        AgentPayload payload = new AgentPayload();
        payload.type = "FAILURE";
        payload.failingNodeId = failingNodeId;
        return payload;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public int getFailingNodeId() { return failingNodeId; }
    public void setFailingNodeId(int failingNodeId) { this.failingNodeId = failingNodeId; }

    public List<Integer> getVisitedNodeIds() { return visitedNodeIds; }
    public void setVisitedNodeIds(List<Integer> visitedNodeIds) { this.visitedNodeIds = visitedNodeIds; }

    public List<FileEntry> getFileList() { return fileList; }
    public void setFileList(List<FileEntry> fileList) { this.fileList = fileList; }

    public int getHopCount() { return hopCount; }
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }
}

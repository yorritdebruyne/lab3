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
 * This is modern, debuggable (you can read it in logs), and language-agnostic.
 *
 * Fields:
 *   type          — which agent this is: "SYNC" or "FAILURE"
 *   failingNodeId — (FAILURE only) ring ID of the node that crashed
 *   startNodeId   — (FAILURE only) ring ID of the node that created the agent
 *                   used to detect when the agent has gone around the full ring
 *   fileList      — the agent's accumulated list of FileEntries
 *                   grows as the agent visits each node and learns about more files
 */
public class AgentPayload {

    // Tells the receiving node which agent type this is
    private String type;

    // FAILURE agent gives the ID of the node that crashed.
    // SYNC agent does not use this so returns -1.
    private int failingNodeId = -1;

    // FAILURE agent gives the ID of the node that originally created the agent.
    // When the agent arrives back at this node, it termminates.
    // SYNC agent does not use this so returns -1.
    private int startNodeId = -1;

    // The file list this agent carries.
    // Merged list of SYNC payloads with the full file registry from the SYNC agent.
    // FAILURE agent does not use this beause each node reads its own local file.
    private List<FileEntry> fileList = new ArrayList<>();


    // Empty constructor for default type none
    public AgentPayload() {}

    // Factory method for a SYNC agent payload
    static public AgentPayload forSync (List<FileEntry> fileList){
        AgentPayload payload = new AgentPayload();
        payload.type = "SYNC";
        payload.fileList = fileList;
        return payload;
    }

    // Factory method for a FAILURE agent payload
    static public AgentPayload forFailure (int failingNodeId, int startNodeId){
        AgentPayload payload = new AgentPayload();
        payload.type = "FAILURE";
        payload.failingNodeId = failingNodeId;
        payload.startNodeId = startNodeId;
        return payload;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getFailingNodeId() {
        return failingNodeId;
    }

    public void setFailingNodeId(int failingNodeId) {
        this.failingNodeId = failingNodeId;
    }

    public int getStartNodeId() {
        return startNodeId;
    }

    public void setStartNodeId(int startNodeId) {
        this.startNodeId = startNodeId;
    }

    public List<FileEntry> getFileList() {
        return fileList;
    }

    public void setFileList(List<FileEntry> fileList) {
        this.fileList = fileList;
    }
}

package org.example.lab3.model;

/**
 * Response model for GET /api/files/owner?filename=...
 *
 * Returns the node responsible for storing a given file.
 * Includes the port so callers can build correct REST URLs
 * for localhost multi-node testing where each node uses a
 * different port.
 */
public class FileOwnerResponse {
    private int NodeId;
    private String ip;
    private int port; // Needed for correct URL building on localhost

    // No-arg constructor for Jackson
    public FileOwnerResponse() {}

    public FileOwnerResponse(int nodeId, String ip, int port) {
        NodeId = nodeId;
        this.ip = ip;
        this.port = port;
    }

    public int getNodeId() {return NodeId;}
    public String getIp() {return ip;}
    public int getPort() { return port; }

    public void setNodeId(int nodeId) {
        NodeId = nodeId;
    }
    public void setIp(String ip) {
        this.ip = ip;
    }
    public void setPort(int port) {
        this.port = port;
    }
}

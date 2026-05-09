package org.example.lab3.model;

/**
 * NodeInfo represents a single node registered in the naming server's ring map.
 *
 * Fields:
 *   id    — the node's position on the consistent hashing ring [0, 32768]
 *   name  — the human-readable node name (e.g. "nodeA")
 *   ip    — the IP address the node is reachable on
 *   port  — the HTTP port the node's REST endpoints are listening on
 *
 * The port field was added to support localhost testing where multiple nodes
 * share the same IP (127.0.0.1) but use different ports (8081, 8082, 8083).
 * Without the port, ShutdownService and FailureHandler cannot build correct
 * URLs when running on a single machine. On a real distributed deployment
 * (each node on its own VM) the port would always be 8081 and this field
 * would be ignored, but storing it costs nothing.
 */
public class NodeInfo {
    private int id;
    private String name;
    private String ip;
    private int port;  // HTTP peer port — needed for localhost multi-node testing

    // No-arg constructor required by Jackson for JSON deserialization
    public NodeInfo(){}

    public NodeInfo(int id, String name, String ip){
        this.id = id;
        this.name = name;
        this.ip = ip;
        this.port = 8081;
    }

    public NodeInfo(int id, String name, String ip, int port) {
        this.id   = id;
        this.name = name;
        this.ip   = ip;
        this.port = port;
    }

    public int getId() {return id;}
    public void setId(int id) {this.id = id;}
    public String getName() {return name;}
    public void setName(String name) {this.name = name;}
    public String getIp() {return ip;}
    public void setIp(String ip) {this.ip = ip;}
    public int    getPort()          { return port; }
    public void   setPort(int port)  { this.port = port; }

    @Override
    public String toString() {
        return "NodeInfo{id=" + id + ", name='" + name
                + "', ip='" + ip + "', port=" + port + "}";
    }
}

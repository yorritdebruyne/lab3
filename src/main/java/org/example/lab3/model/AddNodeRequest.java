package org.example.lab3.model;

public class AddNodeRequest {
    private String name;
    private String ip;
    private int port = 8081; // Default for backwards compatibility
    public String getName() {return name;}
    public void setName(String name) {this.name = name;}
    public String getIp() {return ip;}
    public void setIp(String ip) {this.ip = ip;}
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}

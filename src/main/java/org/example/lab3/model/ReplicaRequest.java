package org.example.lab3.model;

/**
 * Request body for POST /api/files/replicate
 *
 * A node sends this when it wants to know where to replicate a local file.
 * The naming server uses the filename to calculate its hash and find the
 * correct replica node using the ring algorithm (node.hash < file.hash).
 */

public class ReplicaRequest {
    private String filename;
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
}

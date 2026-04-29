package org.example.lab3.node;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the ownership log for a single file in the system.
 *
 * The OWNER node (= the replica node, determined by the naming server)
 * maintains one FileLog per file it is responsible for.
 *
 * Fields:
 *   filename         — the file's name (e.g. "doc1.txt")
 *   downloadLocation — IP of the node where the original file lives
 *   replicaLocations — list of IPs where copies exist (usually just this node)
 *
 * This log is updated when:
 *   - A new replica is added
 *   - A node shuts down and its files are moved
 *   - A file is deleted
 */
public class FileLog {
    private String filename;
    private String downloadLocation;
    private List<String> replicaLocations = new ArrayList<>();

    public FileLog() {}

    public FileLog(String filename, String downloadLocation, String replicaLocation) {
        this.filename = filename;
        this.downloadLocation = downloadLocation;
        this.replicaLocations.add(replicaLocation);
    }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getDownloadLocation() { return downloadLocation; }
    public void setDownloadLocation(String loc) { this.downloadLocation = loc; }

    public List<String> getReplicaLocations() { return replicaLocations; }
    public void setReplicaLocations(List<String> locs) { this.replicaLocations = locs; }

    public void addReplicaLocation(String ip) {
        if (!replicaLocations.contains(ip)) replicaLocations.add(ip);
    }

    public void removeReplicaLocation(String ip) {
        replicaLocations.remove(ip);
    }
}
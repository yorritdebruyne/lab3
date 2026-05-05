package org.example.lab3.node;

/**
 * Represents a single file entry in the system-wide FileRegistry.
 *
 * Every node maintains a FileRegistry containing one FileEntry
 * for every file known in the entire System Y network — not just
 * its own local files, but every file on every node.
 *
 * FileEntries travel between nodes as part of an AgentPayload (JSON).
 * The SyncAgent builds a list of FileEntries, wraps it in an AgentPayload,
 * and POSTs it to the next node. The receiving node merges the incoming
 * entries into its own FileRegistry via AgentController.
 *
 * Fields:
 *   filename — the name of the file (e.g. "doc1.txt", "image5.png")
 *   locked   — whether this file is currently locked for writing.
 *              true  = a node has the file open for writing — no other
 *                      node may write to any copy until the lock is released.
 *              false = the file is available for reading or writing.
 *
 * Note: this class does NOT implement Serializable. We use JSON over
 * REST (via AgentPayload) to transfer data between nodes instead of
 * Java binary serialization. Jackson handles the conversion automatically
 * using the no-arg constructor and standard getters/setters.
 */

public class FileEntry {

    // The file's name, e.g. "doc1.txt" or "image5.png"
    private String filename;

    // Whether this file is currently locked for writing across the system.
    // A lock set on one node propagates to all other nodes on the next
    // SyncAgent cycle, preventing concurrent writes to any copy.
    private boolean locked;

    // No-arg constructor required by Jackson for JSON deserialization.
    // Jackson reads the JSON fields and calls the setters to populate them.
    public FileEntry(){}

    /**
     * Creates a new unlocked FileEntry for the given filename.
     * Files always start as unlocked when first registered.
     *
     * @param filename The name of the file to track.
     */
    public FileEntry(String filename){
        this.filename = filename;
        this.locked = false;
    }

    /**
     * Creates a FileEntry with an explicit lock state.
     * Used when reconstructing an entry from a neighbour's file list
     * that already carries lock information.
     *
     * @param filename The name of the file.
     * @param locked   Whether the file is currently locked.
     */
    public FileEntry(String filename, boolean locked) {
        this.filename = filename;
        this.locked   = locked;
    }

    public String  getFilename()           { return filename; }
    public void    setFilename(String f)   { this.filename = f; }

    public boolean isLocked()              { return locked; }
    public void    setLocked(boolean lock) { this.locked = lock; }

    @Override
    public String toString() {
        return "FileEntry{filename='" + filename + "', locked=" + locked + "}";
    }

}

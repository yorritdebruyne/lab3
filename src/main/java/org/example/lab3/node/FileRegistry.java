package org.example.lab3.node;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FileRegistry is the system-wide file catalogue maintained by each node.
 *
 * Every node in System Y keeps its own copy of this registry.
 * It contains one FileEntry for every file known anywhere in the network —
 * not just files stored locally, but files on every other node too.
 *
 * === How it stays up to date ===
 *
 * The SyncAgent runs a background loop on each node. Every few seconds it:
 *   1. Scans the local/ folder and calls addFile() for any new files
 *   2. Wraps the full registry in an AgentPayload (JSON)
 *   3. POSTs it to the next neighbour via AgentDispatcher
 *   4. The neighbour's AgentController calls merge() with the incoming list
 *
 * This hop-by-hop propagation means that within one full ring traversal
 * (N nodes × sync interval), every node knows about every file.
 *
 * === Distributed locking ===
 *
 * When a node wants to write to a file exclusively:
 *   1. It calls lock(filename) — sets locked=true in its own registry
 *   2. On the next sync cycle, the locked entry travels to the next node
 *   3. merge() sees the incoming lock flag and applies it locally
 *   4. After one full ring traversal, all nodes have the file locked
 *   5. unlock(filename) releases it the same way
 *
 * === Thread safety ===
 *
 * Uses ConcurrentHashMap because the SyncAgent background thread and
 * REST call threads (lock/unlock/filelist) all access this map concurrently.
 * ConcurrentHashMap handles this without needing explicit synchronization.
 */

@Component
@Profile("node")
public class FileRegistry {

    // Map from filename → FileEntry.
    // Key = filename string for fast O(1) lookup.
    // ConcurrentHashMap: thread-safe without locking the entire map.
    private final Map<String, FileEntry> entries = new ConcurrentHashMap<>();

    /**
     * Adds a new file to the registry if it is not already known.
     * New files always start as unlocked.
     *
     * Uses putIfAbsent so that an existing entry (e.g. one that is already
     * locked) is never accidentally overwritten by a fresh unlocked entry.
     *
     * @param filename The name of the file to register.
     */
    public void addFile(String filename) {
        entries.putIfAbsent(filename, new FileEntry(filename));
        System.out.println("[FileRegistry] Registered: " + filename);
    }

    /**
     * Locks a file so no other node can write to any copy of it.
     * If the file is not in the registry yet, it is added first.
     *
     * @param filename The file to lock.
     */
    public void lock(String filename){
        entries.computeIfAbsent(filename, FileEntry::new).setLocked(true);
        System.out.println("[FileRegistry] Locked: " + filename);
    }

    /**
     * Releases the write lock on a file.
     * Does nothing if the file is not in the registry.
     *
     * @param filename The file to unlock.
     */
    public void unlock(String filename){
        FileEntry entry = entries.get(filename);
        if (entry != null){
            entry.setLocked(false);
            System.out.println("[FileRegistry] Unlocked: " + filename);
        }
    }

    /**
     * Returns true if the given file is currently locked.
     *
     * @param filename The file to check.
     */
    public boolean isLocked(String filename){
        FileEntry entry = entries.get(filename);
        if (entry == null) {
            return false;
        }
        return entry.isLocked();
    }

    /**
     * Returns all known FileEntries as a list.
     * Used by GET /node/filelist so the SyncAgent can send our
     * complete registry to the next neighbour.
     *
     * @return A new list containing all current entries.
     */
    public List<FileEntry> getAll() {
        return new ArrayList<>(entries.values());
    }

    /**
     * Merges another node's file list into our own registry.
     *
     * Called by AgentController when a SYNC AgentPayload arrives.
     * The payload's fileList contains everything the sending node knows about.
     *
     * For each entry in the incoming list:
     *   - File not yet in our registry → add it with its current lock state
     *   - File already known, incoming is locked, ours is not → lock it
     *     (lock propagation: a write lock on one node spreads to all nodes)
     *   - File already known, incoming is unlocked, ours is locked → unlock it
     *     (unlock propagation: release travels the same way)
     *   - File already known, lock state matches → no change needed
     *
     * After one full ring traversal of sync cycles, every node's registry
     * converges to the same state.
     *
     * @param incoming The file list extracted from an incoming AgentPayload.
     */
    public void merge(List<FileEntry> incoming) {
        // Sync only adds new file names — it never changes lock state.
        // Lock/unlock changes are applied by direct REST broadcast (NodeController),
        // so stale sync payloads cannot accidentally override a freshly-set lock.
        for (FileEntry remote : incoming) {
            entries.putIfAbsent(remote.getFilename(), new FileEntry(remote.getFilename()));
        }
    }

    /**
     * Returns the number of files currently in the registry.
     * Useful for logging and debugging.
     */
    public int size() {
        return entries.size();
    }

    @Override
    public String toString() {
        return "FileRegistry{" + entries.values() + "}";
    }
}

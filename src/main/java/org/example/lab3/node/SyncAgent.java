package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.List;

/**
 * SyncAgent is a permanent background service that runs on each node.
 *
 * It is NOT a mobile agent — it stays on its own node and periodically
 * syncs its FileRegistry with the next neighbour by calling
 * POST /agent/receive with a SYNC payload.
 *
 * The SYNC payload carries the current node's FileRegistry contents.
 * The receiving node merges the incoming list into its own registry
 * and responds — but we don't need the response here because the
 * AgentController on the next node handles the merge itself.
 *
 * === What happens each sync cycle ===
 *
 * 1. Scan local/ folder → register any new files in FileRegistry
 * 2. Build a SYNC AgentPayload containing our full FileRegistry
 * 3. POST it to the next node via AgentDispatcher
 * 4. The next node's AgentController merges it and does NOT forward it
 *    (SYNC agents don't travel — each node has its own SyncAgent)
 *
 * === Distributed locking ===
 *
 * When a file is locked here (via PUT /node/lock/{filename}),
 * the lock flag is set in FileRegistry. On the next sync cycle,
 * the locked entry is included in the payload sent to the next node.
 * That node merges it, sees the lock, and propagates it further
 * on its own next sync cycle. After one full ring traversal,
 * every node knows the file is locked.
 *
 * @Order(3) ensures this starts after BootstrapService (@Order defaults)
 * and FileScanner (@Order(2)).
 */
@Service
@Profile("node")
@Order(3)
public class SyncAgent {

    @Value("${node.local.dir:local}")
    private String localDir;

    @Value("${sync.interval.ms:5000}")
    private long syncIntervalMs;

    private final NodeState       state;
    private final FileRegistry    fileRegistry;
    private final AgentDispatcher dispatcher;
    private final NodeIpLookup    ipLookup;

    public SyncAgent(NodeState state, FileRegistry fileRegistry,
                     AgentDispatcher dispatcher, NodeIpLookup ipLookup) {
        this.state        = state;
        this.fileRegistry = fileRegistry;
        this.dispatcher   = dispatcher;
        this.ipLookup     = ipLookup;
    }


    /**
     * Starts the sync loop in a background daemon thread after Spring is ready.
     * We wait 4 seconds so bootstrap and neighbour discovery have settled first.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void startSyncLoop() {
        Thread t = new Thread(this::syncLoop, "sync-agent");
        t.setDaemon(true);
        t.start();
    }

    /**
     * The main sync loop — runs forever until the node shuts down.
     */
    private void syncLoop() {
        // Wait for bootstrap + neighbour updates to settle
        try { Thread.sleep(4000); } catch (InterruptedException ignored) {}

        System.out.println("[SyncAgent] Started on node " + state.getCurrentId());

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Step 1: scan local folder and register any new files
                scanLocalFiles();

                // Steps 2 + 3: send our registry to the next node
                pushToNextNode();

                Thread.sleep(syncIntervalMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[SyncAgent] Stopping.");
                break;
            } catch (Exception e) {
                // Never let an exception kill the sync loop permanently
                System.err.println("[SyncAgent] Error: " + e.getMessage());
            }
        }
    }

    /**
     * Scans the local/ folder and registers any new files in the FileRegistry.
     * This is how this node's own files become known to the system.
     */
    private void scanLocalFiles() {
        try {
            Path dir = Paths.get(localDir);
            if (!Files.exists(dir)) return;

            Files.list(dir)
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .forEach(fileRegistry::addFile);

        } catch (Exception e) {
            System.err.println("[SyncAgent] Could not scan local folder: " + e.getMessage());
        }
    }

    /**
     * Builds a SYNC payload from our current FileRegistry and sends it
     * to the next neighbour via AgentDispatcher.
     *
     * Skipped if we are alone in the ring (next == self).
     */
    private void pushToNextNode() {
        int myId   = state.getCurrentId();
        int nextId = state.getNextId();

        // Don't send if alone in the ring or not yet initialised
        if (nextId == myId || nextId == -1) return;

        String nextIp = ipLookup.getIpForId(nextId);
        if (nextIp == null) return;

        // Build the payload with everything we know
        List<FileEntry> allEntries = fileRegistry.getAll();
        AgentPayload payload = AgentPayload.forSync(allEntries);

        dispatcher.dispatch(nextIp, payload);
    }

    /**
     * Locks a file in the local registry.
     * The lock propagates to all nodes on the next sync cycle.
     *
     * @param filename The file to lock.
     */
    public void requestLock(String filename) {
        fileRegistry.lock(filename);
        System.out.println("[SyncAgent] Lock requested for: " + filename);
    }

    /**
     * Releases a lock on a file in the local registry.
     * The unlock propagates to all nodes on the next sync cycle.
     *
     * @param filename The file to unlock.
     */
    public void releaseLock(String filename) {
        fileRegistry.unlock(filename);
        System.out.println("[SyncAgent] Lock released for: " + filename);
    }
}

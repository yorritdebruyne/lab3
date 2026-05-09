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
 * the locked entry travels to the next node. That node merges it,
 * sees the lock, and propagates it further on its own next sync cycle.
 * After one full ring traversal every node knows the file is locked.
 *
 * === Port handling ===
 *
 * NodeIpLookup returns "ip:port" (e.g. "127.0.0.1:8082").
 * SyncAgent passes this directly to AgentDispatcher which no longer
 * appends its own port — fixing localhost multi-node testing.
 *
 * @Order(3) ensures this starts after BootstrapService and FileScanner.
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
     * Waits 4 seconds so bootstrap and neighbour discovery have settled first.
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
        try { Thread.sleep(4000); } catch (InterruptedException ignored) {}

        System.out.println("[SyncAgent] Started on node " + state.getCurrentId());

        while (!Thread.currentThread().isInterrupted()) {
            try {
                scanLocalFiles();
                pushToNextNode();
                Thread.sleep(syncIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[SyncAgent] Stopping.");
                break;
            } catch (Exception e) {
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
     * NodeIpLookup returns "ip:port" which is passed directly to
     * AgentDispatcher — no port manipulation needed here.
     *
     * Skipped if we are alone in the ring (next == self).
     */
    private void pushToNextNode() {
        int myId   = state.getCurrentId();
        int nextId = state.getNextId();

        if (nextId == myId || nextId == -1) return;

        // ipLookup returns "ip:port" — pass directly to dispatcher
        String nextAddr = ipLookup.getIpForId(nextId);
        if (nextAddr == null) return;

        List<FileEntry> allEntries = fileRegistry.getAll();
        AgentPayload payload = AgentPayload.forSync(allEntries);

        dispatcher.dispatch(nextAddr, payload);
    }

    /**
     * Locks a file in the local registry.
     * The lock propagates to all nodes on the next sync cycle.
     */
    public void requestLock(String filename) {
        fileRegistry.lock(filename);
        System.out.println("[SyncAgent] Lock requested for: " + filename);
    }

    /**
     * Releases a lock on a file in the local registry.
     * The unlock propagates to all nodes on the next sync cycle.
     */
    public void releaseLock(String filename) {
        fileRegistry.unlock(filename);
        System.out.println("[SyncAgent] Lock released for: " + filename);
    }
}
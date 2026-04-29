package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * NodeController exposes REST endpoints that OTHER nodes call to update
 * this node's state, or to check whether this node is alive.
 *
 * Lab 4 endpoints:
 *   PUT  /node/prev   — set our prevId
 *   PUT  /node/next   — set our nextId
 *   GET  /node/ping   — heartbeat check, returns "pong"
 *   GET  /node/state  — returns full NodeState as JSON (debugging)
 *
 * Lab 5 additions:
 *   DELETE /node/replica/{filename} — delete a replica file from replicas/ folder
 *                                     called by FolderWatcher on another node when
 *                                     that node's local file gets deleted
 *   GET    /node/files              — returns list of files in local/ folder
 *                                     called by ReplicationShutdownService to check
 *                                     the edge case during shutdown
 */
@RestController
@RequestMapping("/node")
@Profile("node")
public class NodeController {

    private final NodeState      state;
    private final FileLogService fileLogService;

    // Folder where replica files are stored — matches TcpFileServer
    @Value("${node.replicas.dir:replicas}")
    private String replicasDir;

    // Folder where local files are stored
    @Value("${node.local.dir:local}")
    private String localDir;

    public NodeController(NodeState state, FileLogService fileLogService) {
        this.state          = state;
        this.fileLogService = fileLogService;
    }

    // =========================================================================
    // Lab 4 endpoints — unchanged
    // =========================================================================

    /**
     * Updates this node's prevId.
     * Called by MulticastReceiver of an existing node when a new node joins,
     * or by ShutdownService/FailureHandler when stitching the ring back together.
     */
    @PutMapping("/prev")
    public ResponseEntity<Void> updatePrev(@RequestBody NeighbourUpdate req) {
        System.out.println("[NodeController] Setting prevId = " + req.getId());
        state.setPrevId(req.getId());
        return ResponseEntity.ok().build();
    }

    /**
     * Updates this node's nextId.
     */
    @PutMapping("/next")
    public ResponseEntity<Void> updateNext(@RequestBody NeighbourUpdate req) {
        System.out.println("[NodeController] Setting nextId = " + req.getId());
        state.setNextId(req.getId());
        return ResponseEntity.ok().build();
    }

    /**
     * Simple ping / heartbeat.
     * Returns HTTP 200 "pong" if this node is alive.
     * PingScheduler on neighbours calls this periodically — if it throws
     * a connection exception, FailureHandler takes over.
     */
    @GetMapping("/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("pong");
    }

    /**
     * Returns the full current state of this node as JSON.
     * Very useful during debugging — open in a browser.
     * Example: http://192.168.0.5:8081/node/state
     */
    @GetMapping("/state")
    public ResponseEntity<NodeState> getState() {
        return ResponseEntity.ok(state);
    }

    // =========================================================================
    // Lab 5 endpoints — new
    // =========================================================================

    /**
     * Deletes a replica file from this node's replicas/ folder and removes its log.
     *
     * Called by FolderWatcher on the ORIGINATING node when a local file is deleted.
     * Example flow:
     *   - node2 deletes doc1.txt from its local/ folder
     *   - FolderWatcher on node2 detects the deletion
     *   - FolderWatcher calls DELETE /node/replica/doc1.txt on node3 (the owner)
     *   - This endpoint deletes replicas/doc1.txt and logs/doc1.txt.json on node3
     */
    @DeleteMapping("/replica/{filename}")
    public ResponseEntity<Void> deleteReplica(@PathVariable String filename) {
        System.out.println("[NodeController] Deleting replica: " + filename);
        try {
            // Delete the actual replica file
            Files.deleteIfExists(Paths.get(replicasDir, filename));
            // Delete the log for this file
            fileLogService.delete(filename);
            System.out.println("[NodeController] Deleted replica and log for: " + filename);
        } catch (Exception e) {
            System.err.println("[NodeController] Could not delete replica: " + e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the list of filenames in this node's local/ folder.
     *
     * Called by ReplicationShutdownService on a shutting-down node to check
     * the edge case: if the previous node already has a file locally, the
     * replica should go to prev.prev instead.
     *
     * Example: http://192.168.0.5:8081/node/files
     * Returns: ["doc1.txt", "image2.png"]
     */
    @GetMapping("/files")
    public ResponseEntity<List<String>> listLocalFiles() {
        try {
            // Ensure the local directory exists before listing
            Files.createDirectories(Paths.get(localDir));

            List<String> files = Files.list(Paths.get(localDir))
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());

            return ResponseEntity.ok(files);
        } catch (Exception e) {
            System.err.println("[NodeController] Could not list local files: " + e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    // =========================================================================
    // Inner class — request body for PUT /node/prev and PUT /node/next
    // =========================================================================

    /**
     * Simple wrapper around a single integer ring ID.
     * Jackson deserialises {"id": 12345} into this automatically.
     */
    public static class NeighbourUpdate {
        private int id;
        public int  getId()        { return id; }
        public void setId(int id)  { this.id = id; }
    }
}
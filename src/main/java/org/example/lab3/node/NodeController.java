package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
@CrossOrigin(origins = "*")
public class NodeController {

    private final NodeState      state;
    private final FileLogService fileLogService;
    private final FileRegistry   fileRegistry;
    private final NodeIpLookup   ipLookup;
    private final NodeJoinRedistributionService joinRedistribution;
    private final ReplicationService replicationService;

    @Value("${node.replicas.dir:replicas}")
    private String replicasDir;

    @Value("${node.local.dir:local}")
    private String localDir;

    public NodeController(NodeState state, FileLogService fileLogService,
                          FileRegistry fileRegistry, NodeIpLookup ipLookup,
                          NodeJoinRedistributionService joinRedistribution,
                          ReplicationService replicationService) {
        this.state              = state;
        this.fileLogService     = fileLogService;
        this.fileRegistry       = fileRegistry;
        this.ipLookup           = ipLookup;
        this.joinRedistribution = joinRedistribution;
        this.replicationService = replicationService;
    }

    // =========================================================================
    // Lab 4 endpoints
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
     *
     * If the new next is a DIFFERENT node, a newcomer may have slotted in just
     * after us in the ring — which means some files we currently own may now
     * belong to it. We kick off a (non-blocking) join redistribution pass so
     * those files are handed over. This is the REST join path; the multicast
     * join path triggers the same pass from MulticastReceiver.
     */
    @PutMapping("/next")
    public ResponseEntity<Void> updateNext(@RequestBody NeighbourUpdate req) {
        int oldNext = state.getNextId();
        System.out.println("[NodeController] Setting nextId = " + req.getId());
        state.setNextId(req.getId());

        if (joinRedistribution != null
                && req.getId() != oldNext
                && req.getId() != state.getCurrentId()) {
            joinRedistribution.onNextNeighbourChanged();
        }
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
    // Lab 5 endpoints
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

    /**
     * Returns the filenames ACTUALLY stored in this node's replicas/ folder —
     * the files this node owns and physically holds a replica for.
     *
     * Distinct from /node/filelist (the gossiped FileRegistry, which lists every
     * filename known anywhere in the system). The GUI uses this so the "Replica
     * Files" panel shows what the node truly holds, not every known name.
     */
    @GetMapping("/replicas")
    public ResponseEntity<List<String>> listReplicaFiles() {
        try {
            Files.createDirectories(Paths.get(replicasDir));
            List<String> files = Files.list(Paths.get(replicasDir))
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
            return ResponseEntity.ok(files);
        } catch (Exception e) {
            System.err.println("[NodeController] Could not list replica files: " + e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    // =========================================================================
    // EXPANSION — GUI file upload
    // =========================================================================

    /**
     * Accepts a file uploaded from the GUI, stores it in this node's local/ folder,
     * and replicates it to its owner via the EXISTING replication pipeline — the same
     * path FileScanner and FolderWatcher use. The owner is decided purely by the
     * file's hash; this endpoint adds no new placement logic.
     *
     * Hardened against the common upload risks:
     *   - empty upload or blank filename            → 400 Bad Request
     *   - directory components / path traversal      → stripped, and re-validated to
     *                                                   stay inside local/ (400 if not)
     *   - oversized upload                           → 413 (capped by
     *                                                   spring.servlet.multipart.*)
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No file provided or file is empty"));
        }

        // Keep only the base name and reject traversal attempts (e.g. "../../x").
        String raw = file.getOriginalFilename();
        if (raw == null || raw.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing filename"));
        }
        String filename = Paths.get(raw).getFileName().toString();
        if (filename.isBlank() || filename.contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid filename"));
        }

        try {
            Path localRoot = Paths.get(localDir).toAbsolutePath().normalize();
            Files.createDirectories(localRoot);
            Path dest = localRoot.resolve(filename).normalize();

            // Defence in depth: the resolved path must stay inside local/.
            if (!dest.startsWith(localRoot)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid file path"));
            }

            file.transferTo(dest);                  // write into local/
            fileRegistry.addFile(filename);         // make the file known to the ring
            replicationService.replicate(filename); // hash → owner → TCP (existing pipeline)

            System.out.println("[NodeController] Uploaded and replicated: " + filename);
            return ResponseEntity.ok(Map.of(
                    "status", "stored",
                    "filename", filename,
                    "node", state.getName() == null ? "" : state.getName()
            ));
        } catch (Exception e) {
            System.err.println("[NodeController] Upload failed for " + filename + ": " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Could not store file: " + e.getMessage()));
        }
    }

    // =========================================================================
    // Lab 6 endpoints
    // =========================================================================
    /**
     * Returns this node's full FileRegistry as a JSON array.
     * Called by the SyncAgent on the previous neighbour every sync cycle.
     * The caller merges the result into its own registry via AgentController.
     */
    @GetMapping("/filelist")
    public ResponseEntity<List<FileEntry>> getFileList() {
        return ResponseEntity.ok(fileRegistry.getAll());
    }

    /**
     * Locks a file in this node's FileRegistry and broadcasts the lock to all
     * neighbours so that every node in the ring is updated immediately.
     * The {@code broadcast} parameter prevents recursive re-broadcast when
     * neighbours call this endpoint in response to the initial broadcast.
     */
    @PutMapping("/lock/{filename}")
    public ResponseEntity<Void> lockFile(@PathVariable String filename,
                                         @RequestParam(defaultValue = "true") boolean broadcast) {
        System.out.println("[NodeController] Locking: " + filename);
        fileRegistry.lock(filename);
        if (broadcast) broadcastLockState(filename, true);
        return ResponseEntity.ok().build();
    }

    /**
     * Releases the lock on a file in this node's FileRegistry and broadcasts
     * the unlock to all neighbours for immediate ring-wide consistency.
     */
    @PutMapping("/unlock/{filename}")
    public ResponseEntity<Void> unlockFile(@PathVariable String filename,
                                            @RequestParam(defaultValue = "true") boolean broadcast) {
        System.out.println("[NodeController] Unlocking: " + filename);
        fileRegistry.unlock(filename);
        if (broadcast) broadcastLockState(filename, false);
        return ResponseEntity.ok().build();
    }

    private void broadcastLockState(String filename, boolean locked) {
        if (ipLookup == null) return;
        String action = locked ? "lock" : "unlock";
        int myId = state.getCurrentId();
        RestTemplate rt = new RestTemplate();
        Stream.of(state.getNextId(), state.getPrevId())
              .filter(id -> id != myId && id != -1)
              .distinct()
              .forEach(id -> {
                  String addr = ipLookup.getIpForId(id);
                  if (addr == null) return;
                  try {
                      rt.put("http://" + addr + "/node/" + action + "/" + filename + "?broadcast=false", null);
                      System.out.println("[NodeController] Broadcast " + action + " to " + addr);
                  } catch (Exception e) {
                      System.err.println("[NodeController] Broadcast " + action + " failed for " + addr
                              + ": " + e.getMessage());
                  }
              });
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
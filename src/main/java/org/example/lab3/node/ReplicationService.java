package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ReplicationService handles replicating a single local file to the correct node.
 *
 * === Algorithm (Phase: Starting, step 3) ===
 *
 * Given a filename:
 *   1. Ask the naming server POST /api/files/replicate with the filename
 *      → returns the NodeInfo of the replica node
 *        (= the node with the largest ID still SMALLER than the file hash)
 *   2. If the replica node IS this node → store a self-referencing log (no TCP needed)
 *   3. Otherwise → send the file over TCP to the replica node
 *
 * Called by:
 *   - FileScanner   on startup (for all existing local files)
 *   - FolderWatcher when a new file is added
 */
@Service
@Profile("node")
public class ReplicationService {

    @Value("${namingserver.url}")
    private String namingServerUrl;

    @Value("${node.local.dir:local}")
    private String localDir;

    private final NodeState      state;
    private final TcpFileClient  tcpClient;
    private final FileLogService fileLogService;
    private final EventPublisher eventPublisher;
    private final NodeIpLookup   ipLookup;
    private final RestTemplate   restTemplate = new RestTemplate();

    // Remembers the owner id we last replicated each local file to. Lets the
    // reconcile sweep (replicateAllLocal) detect when ownership has CHANGED —
    // e.g. after an owner crashes and a different node becomes the new owner —
    // so it only re-pushes (and announces) on a real change, not every cycle.
    private final Map<String, Integer> lastOwnerId = new ConcurrentHashMap<>();

    public ReplicationService(NodeState state, TcpFileClient tcpClient,
                              FileLogService fileLogService, EventPublisher eventPublisher,
                              NodeIpLookup ipLookup) {
        this.state          = state;
        this.tcpClient      = tcpClient;
        this.fileLogService = fileLogService;
        this.eventPublisher = eventPublisher;
        this.ipLookup       = ipLookup;
    }

    /**
     * Replicates a single local file to the appropriate node in the ring.
     *
     * @param filename The name of the file to replicate (e.g. "doc1.txt")
     */
    public void replicate(String filename) {
        System.out.println("[ReplicationService] Replicating: " + filename);
        NodeInfo replicaNode = resolveOwner(filename);
        if (replicaNode == null) return;
        sendToOwner(filename, replicaNode, true);
    }

    /**
     * Re-replicates EVERY file in this node's local/ folder to its CURRENT owner —
     * but only when the owner has changed since we last pushed it.
     *
     * This is the self-healing pass. When an owner node crashes, the file it owned
     * now hashes to a different node; the surviving origin (which still holds the
     * local/ copy) re-pushes the file to that new owner here, so the replica is
     * restored to the ring instead of being lost. On a steady ring nothing changes,
     * so this is a cheap no-op (a naming-server lookup, no TCP, no event).
     *
     * Called every interval by {@link ReconcileScheduler}, alongside the existing
     * replica-cleanup pass. No new placement logic — ownership is still decided
     * solely by the naming server.
     */
    public void replicateAllLocal() {
        List<String> localFiles = listLocalFiles();
        for (String filename : localFiles) {
            NodeInfo owner = resolveOwner(filename);
            if (owner == null) continue;

            Integer previous = lastOwnerId.get(filename);
            if (previous != null && previous == owner.getId()) {
                // Owner unchanged — replica already where it should be. Skip silently
                // (no redundant TCP transfer, no event-feed spam every cycle).
                continue;
            }

            System.out.println("[ReplicationService] Owner of " + filename
                    + " changed (" + previous + " → " + owner.getId() + ") — re-replicating.");
            sendToOwner(filename, owner, true);
        }
    }

    // Asks the naming server which node should hold the replica for this file.
    // Returns null on any failure (caller skips the file this round).
    private NodeInfo resolveOwner(String filename) {
        NodeInfo replicaNode;
        try {
            replicaNode = restTemplate.postForObject(
                    namingServerUrl + "/api/files/replicate",
                    new ReplicaRequestBody(filename),
                    NodeInfo.class
            );
        } catch (Exception e) {
            System.err.println("[ReplicationService] Could not contact naming server: "
                    + e.getMessage());
            return null;
        }
        if (replicaNode == null) {
            System.err.println("[ReplicationService] No replica node found for: " + filename);
        }
        return replicaNode;
    }

    // Sends the local file to its owner (or writes a self-log if we ARE the owner),
    // records the owner so future sweeps can detect changes, and optionally announces
    // the replication on the live feed.
    private void sendToOwner(String filename, NodeInfo replicaNode, boolean announce) {
        System.out.println("[ReplicationService] Replica node for " + filename
                + " → id=" + replicaNode.getId() + " ip=" + replicaNode.getIp());

        // Spec case: we are the OWNER of our own local file. The replica must live on
        // a DIFFERENT node for fault tolerance, so it goes to our PREVIOUS neighbour —
        // NOT a self-log (a self-log leaves the file with no off-node copy, so it would
        // be lost if this node crashed).
        if (replicaNode.getId() == state.getCurrentId()) {
            replicateToPreviousNode(filename, announce);
            return;
        }

        // Send the file over TCP
        Path filePath = Paths.get(localDir, filename);
        if (!Files.exists(filePath)) {
            System.err.println("[ReplicationService] File not found: " + filePath);
            return;
        }

        boolean ok = tcpClient.sendFile(filePath, replicaNode.getIp(), replicaNode.getTcpPort());
        if (ok) {
            lastOwnerId.put(filename, replicaNode.getId());
            if (announce && eventPublisher != null) {
                // Surface the replication in the live feed (covers GUI uploads, startup
                // FileScanner, FolderWatcher, and crash self-heal) — observability only.
                eventPublisher.publish("REPLICATE",
                        "replicated " + filename + " to owner (id " + replicaNode.getId() + ")");
            }
        }
    }

    /**
     * Handles the spec case where THIS node owns its own local file: the replica is
     * placed on the PREVIOUS node in the ring so a copy survives this node crashing.
     *
     * The log written here records download location = us (the origin/owner) and
     * replica location = prev. That "download location == owner" relationship is what
     * lets the redistribution / reconcile sweep recognise this as a self-owned backup
     * and leave it on prev instead of shipping it back to the owner.
     *
     * Falls back to a local self-log only when we are alone in the ring (prev == self)
     * or prev cannot be resolved yet — in those cases lastOwnerId is deliberately NOT
     * cached, so a later sweep retries once a real previous node exists.
     */
    private void replicateToPreviousNode(String filename, boolean announce) {
        int prevId = state.getPrevId();

        if (prevId == state.getCurrentId()) {
            System.out.println("[ReplicationService] Own file " + filename
                    + " and alone in ring → local log only (no off-node copy possible yet).");
            saveSelfLog(filename);
            return;
        }

        NodeInfo prev = ipLookup.getNodeForId(prevId);
        if (prev == null) {
            System.err.println("[ReplicationService] Own file " + filename
                    + " but prev node " + prevId + " not resolvable yet → local log only for now.");
            saveSelfLog(filename);
            return;
        }

        Path filePath = Paths.get(localDir, filename);
        if (!Files.exists(filePath)) {
            System.err.println("[ReplicationService] File not found: " + filePath);
            return;
        }

        boolean ok = tcpClient.sendFile(filePath, prev.getIp(), prev.getTcpPort());
        if (ok) {
            // We own it; the replica now lives on prev. Record the log on our side too.
            FileLog log = new FileLog(filename, state.getIp(), prev.getIp());
            fileLogService.save(log);
            lastOwnerId.put(filename, state.getCurrentId());
            System.out.println("[ReplicationService] Own file " + filename
                    + " → replica placed on previous node id=" + prev.getId());
            if (announce && eventPublisher != null) {
                eventPublisher.publish("REPLICATE",
                        "replicated " + filename + " to previous node (id " + prev.getId()
                                + " — we are the owner)");
            }
        }
    }

    // Writes a self-referencing log (download + replica both = us). Used only when no
    // previous node is available to hold the off-node copy. Does NOT cache lastOwnerId,
    // so the reconcile sweep retries once a previous node exists.
    private void saveSelfLog(String filename) {
        FileLog log = new FileLog(filename, state.getIp(), state.getIp());
        fileLogService.save(log);
    }

    // Lists the filenames currently in this node's local/ folder.
    private List<String> listLocalFiles() {
        try {
            Files.createDirectories(Paths.get(localDir));
            try (var stream = Files.list(Paths.get(localDir))) {
                return stream.filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.err.println("[ReplicationService] Could not list " + localDir + ": " + e.getMessage());
            return List.of();
        }
    }

    private static class ReplicaRequestBody {
        public String filename;
        public ReplicaRequestBody(String f) { this.filename = f; }
        public String getFilename() { return filename; }
    }
}
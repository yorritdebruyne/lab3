package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * NodeJoinRedistributionService — the JOIN counterpart of ReplicationShutdownService.
 *
 * === Why this exists ===
 *
 * The node lifecycle has three moments where files must move between nodes:
 *   - a node LEAVES gracefully  → ReplicationShutdownService (Lab 5)
 *   - a node CRASHES            → FailureAgent                (Lab 6)
 *   - a node JOINS              → (was missing)
 *
 * Without the join case, after an add / remove / re-add the naming server would
 * report a NEW owner for a file while the bytes physically stayed on the OLD
 * owner. This service closes that gap so ownership and storage stay consistent.
 *
 * === When it runs ===
 *
 * It is triggered whenever this node's NEXT neighbour changes to a different
 * node — exactly the moment a newcomer slots in just after us in the ring, which
 * is when files WE currently own may now belong to that newcomer.
 *   - REST join path      → NodeController.updateNext()
 *   - multicast join path → MulticastReceiver (Case A / "was alone")
 *
 * === What it does (mirrors the spec's "previous owner hands files to the new node") ===
 *
 * For each replica we currently hold (= files we are the owner of):
 *   1. Ask the naming server who should own it now (POST /api/files/replicate).
 *   2. If that is still us            → keep it.
 *   3. If it is now a different node  → send the file there over TCP, then
 *                                       delete our local replica + its log.
 *
 * === Why this is robust ===
 *
 *   - Runs on a background daemon thread, so it never blocks ring formation.
 *   - A short settle delay lets the newcomer's TcpFileServer come up and lets the
 *     naming server finish registering it before we ask who the owner is.
 *   - An AtomicBoolean coalesces overlapping triggers (e.g. multicast + REST for
 *     the same join) so only one pass runs at a time.
 *   - Re-checking ownership PER FILE via the naming server makes it correct for
 *     the wrap-around case (a new largest node) without any ring-arc maths, and
 *     idempotent when nothing actually moved.
 */
@Service
@Profile("node")
public class NodeJoinRedistributionService {

    @Value("${node.replicas.dir:replicas}")
    private String replicasDir;

    @Value("${namingserver.url}")
    private String namingServerUrl;

    /** How long to wait after a join before redistributing (lets the newcomer settle). */
    @Value("${node.join.redistribute.delay.ms:1500}")
    private long settleDelayMs;

    private final NodeState      state;
    private final TcpFileClient  tcpClient;
    private final FileLogService fileLogService;
    private final EventPublisher eventPublisher;
    private final RestTemplate   restTemplate = new RestTemplate();

    // Ensures only one redistribution pass runs at a time
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NodeJoinRedistributionService(NodeState state, TcpFileClient tcpClient,
                                         FileLogService fileLogService,
                                         EventPublisher eventPublisher) {
        this.state          = state;
        this.tcpClient      = tcpClient;
        this.fileLogService = fileLogService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Fire-and-forget trigger. Safe to call from REST handlers and the multicast
     * loop — it returns immediately and does the work on a daemon thread.
     */
    public void onNextNeighbourChanged() {
        if (!running.compareAndSet(false, true)) {
            // A pass is already running; since it re-checks ALL owned files, the
            // current join is already covered. No need to queue another.
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(settleDelayMs); // let the newcomer settle + register
                redistribute();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[JoinRedistribution] Pass failed: " + e.getMessage());
            } finally {
                running.set(false);
            }
        }, "join-redistribution");
        t.setDaemon(true);
        t.start();
    }

    /**
     * One redistribution pass: hand off any owned replica that now belongs to a
     * different node. Package-private so it can be unit-tested directly.
     */
    void redistribute() {
        List<Path> replicaFiles = listFiles(replicasDir);
        if (replicaFiles.isEmpty()) return;

        System.out.println("[JoinRedistribution] Re-checking ownership of "
                + replicaFiles.size() + " replica file(s) after a node joined.");

        for (Path replicaFile : replicaFiles) {
            String filename = replicaFile.getFileName().toString();

            NodeInfo owner = lookupOwner(filename);
            if (owner == null || owner.getId() == state.getCurrentId()) {
                continue; // still ours (or unknown) → keep it
            }

            // Self-owned backup: if this replica's origin (download location) IS its
            // owner, the owner also holds the original locally, so the replica is meant
            // to live OFF the owner (on the owner's previous node) for fault tolerance.
            // Shipping it to the owner would collapse both copies onto one node, so keep
            // it here. (This is the JOIN/reconcile counterpart of ReplicationService's
            // "owner replicates its own file to prev" rule.)
            FileLog log = fileLogService.load(filename);
            if (log != null && owner.getIp().equals(log.getDownloadLocation())) {
                System.out.println("[JoinRedistribution] Keeping " + filename
                        + " — self-owned backup for owner id=" + owner.getId()
                        + " (replica belongs off the owner).");
                continue;
            }

            // Ownership moved to the newcomer → transfer the file, then drop our copy.
            boolean ok = tcpClient.sendFile(replicaFile, owner.getIp(), owner.getTcpPort());
            if (ok) {
                try {
                    Files.deleteIfExists(replicaFile);
                    fileLogService.delete(filename);
                    System.out.println("[JoinRedistribution] Handed " + filename
                            + " to new owner id=" + owner.getId()
                            + " (" + owner.getIp() + ") and removed local copy.");
                    if (eventPublisher != null) {
                        eventPublisher.publish("REDISTRIBUTE",
                                "handed " + filename + " to new owner (id " + owner.getId() + ")");
                    }
                } catch (Exception e) {
                    System.err.println("[JoinRedistribution] Transferred " + filename
                            + " but could not clean up locally: " + e.getMessage());
                }
            } else {
                System.err.println("[JoinRedistribution] Failed to transfer "
                        + filename + " — keeping local copy.");
            }
        }
        System.out.println("[JoinRedistribution] Pass complete.");
    }

    /**
     * Asks the naming server which node should own this file right now.
     * Uses the same endpoint as ReplicationService so the answer (and the
     * returned TCP port) is computed by the one authoritative algorithm.
     * Protected so unit tests can stub it without a live naming server.
     */
    protected NodeInfo lookupOwner(String filename) {
        try {
            return restTemplate.postForObject(
                    namingServerUrl + "/api/files/replicate",
                    new ReplicaRequestBody(filename),
                    NodeInfo.class
            );
        } catch (Exception e) {
            System.err.println("[JoinRedistribution] Owner lookup failed for "
                    + filename + ": " + e.getMessage());
            return null;
        }
    }

    private List<Path> listFiles(String dir) {
        try {
            Files.createDirectories(Paths.get(dir));
            try (Stream<Path> stream = Files.list(Paths.get(dir))) {
                return stream.filter(Files::isRegularFile).collect(Collectors.toList());
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    // Request body for POST /api/files/replicate (mirrors ReplicationService)
    private static class ReplicaRequestBody {
        public String filename;
        public ReplicaRequestBody(String f) { this.filename = f; }
        public String getFilename() { return filename; }
    }
}

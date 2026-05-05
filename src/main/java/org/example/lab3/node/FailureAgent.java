package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FailureAgent handles the logic that runs on each node when a
 * FAILURE AgentPayload arrives via POST /agent/receive.
 *
 * Unlike SyncAgent which stays on its own node, the FailureAgent
 * TRAVELS the ring — its payload is forwarded from node to node
 * until it completes a full loop.
 *
 * === What it does on each node it visits ===
 *
 * Given a failingNodeId (the ID of the crashed node):
 *
 * 1. Read all files in the replicas/ folder of the CURRENT node
 *    (files this node is the owner of)
 *
 * 2. For each replica file, load its FileLog and check:
 *    - Is the failingNodeId the download location of this file?
 *      (meaning: the original local copy was on the failed node)
 *
 * 3. If yes — transfer ownership:
 *    Option A: if the new owner (current node) does NOT already have
 *              the file locally → transfer it and update the log
 *    Option B: if the new owner already has the file locally →
 *              only update the log (no transfer needed)
 *
 * === Termination ===
 *
 * The agent terminates when it arrives back at the node that
 * created it (startNodeId == currentNodeId). At that point the
 * AgentController does NOT forward it further.
 *
 * This is a Spring service because it needs access to Spring beans
 * (FileLogService, TcpFileClient, etc.). The AgentController
 * calls its execute() method when a FAILURE payload arrives.
 */
@Service
@Profile("node")
public class FailureAgent {

    @Value("${node.replicas.dir:replicas}")
    private String replicasDir;

    @Value("${node.local.dir:local}")
    private String localDir;

    @Value("${namingserver.url}")
    private String namingServerUrl;

    @Value("${node.peer.port:8081}")
    private int peerPort;

    private final NodeState      state;
    private final FileLogService fileLogService;
    private final NodeIpLookup   ipLookup;
    private final TcpFileClient  tcpClient;
    private final FileRegistry   fileRegistry;
    private final RestTemplate   restTemplate = new RestTemplate();

    public FailureAgent(NodeState state, FileLogService fileLogService,
                        NodeIpLookup ipLookup, TcpFileClient tcpClient,
                        FileRegistry fileRegistry) {
        this.state          = state;
        this.fileLogService = fileLogService;
        this.ipLookup       = ipLookup;
        this.tcpClient      = tcpClient;
        this.fileRegistry   = fileRegistry;
    }

    /**
     * Executes the failure recovery logic on the CURRENT node.
     *
     * Called by AgentController when a FAILURE payload arrives.
     *
     * @param payload The incoming AgentPayload containing failingNodeId.
     */
    public void execute(AgentPayload payload) {
        int failingNodeId = payload.getFailingNodeId();
        System.out.println("[FailureAgent] Running on node " + state.getCurrentId()
                + " — recovering files from failed node " + failingNodeId);

        // Get the IP of the failed node so we can compare with FileLog download locations
        String failingNodeIp = ipLookup.getIpForId(failingNodeId);

        // Step 1: list all replica files this node owns
        List<Path> replicaFiles = listFiles(replicasDir);

        for (Path replicaFile : replicaFiles) {
            String filename = replicaFile.getFileName().toString();

            // Step 2: load the FileLog for this replica
            FileLog log = fileLogService.load(filename);
            if (log == null) continue;

            // Check if the failing node was the download location
            // (i.e. the original local copy was on the failed node)
            boolean failingNodeWasOwner = failingNodeIp != null
                    && log.getDownloadLocation() != null
                    && log.getDownloadLocation().equals(failingNodeIp);

            if (!failingNodeWasOwner) {
                // This file is not affected by this failure — skip it
                continue;
            }

            System.out.println("[FailureAgent] File " + filename
                    + " was owned by failed node — recovering...");

            // Step 3: determine which option applies
            boolean alreadyHaveLocalCopy = Files.exists(Paths.get(localDir, filename));

            if (!alreadyHaveLocalCopy) {
                // Option A: we don't have the file locally.
                // The replica we hold IS the only remaining copy.
                // Update the log: this node becomes the new download location.
                log.setDownloadLocation(state.getIp());
                fileLogService.save(log);

                // Also update the FileRegistry so SyncAgent propagates the change
                fileRegistry.addFile(filename);

                System.out.println("[FailureAgent] Option A: "
                        + filename + " — updated log, this node is new owner.");

            } else {
                // Option B: we already have the file locally.
                // No transfer needed, just update the log to reflect
                // that the download location is now our own IP.
                log.setDownloadLocation(state.getIp());
                log.removeReplicaLocation(failingNodeIp);
                log.addReplicaLocation(state.getIp());
                fileLogService.save(log);

                System.out.println("[FailureAgent] Option B: "
                        + filename + " — already local, updated log only.");
            }
        }

        System.out.println("[FailureAgent] Done on node " + state.getCurrentId());
    }

    // Lists all regular files in a directory. Returns empty list if not found.
    private List<Path> listFiles(String dir) {
        try {
            Files.createDirectories(Paths.get(dir));
            try (var stream = Files.list(Paths.get(dir))) {
                return stream.filter(Files::isRegularFile).collect(Collectors.toList());
            }
        } catch (Exception e) {
            System.err.println("[FailureAgent] Could not list " + dir + ": " + e.getMessage());
            return List.of();
        }
    }
}

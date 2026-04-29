package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.*;

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
    private final RestTemplate   restTemplate = new RestTemplate();

    public ReplicationService(NodeState state, TcpFileClient tcpClient,
                              FileLogService fileLogService) {
        this.state          = state;
        this.tcpClient      = tcpClient;
        this.fileLogService = fileLogService;
    }

    /**
     * Replicates a single local file to the appropriate node in the ring.
     *
     * @param filename The name of the file to replicate (e.g. "doc1.txt")
     */
    public void replicate(String filename) {
        System.out.println("[ReplicationService] Replicating: " + filename);

        // Ask the naming server where this file should go
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
            return;
        }

        if (replicaNode == null) {
            System.err.println("[ReplicationService] No replica node found for: " + filename);
            return;
        }

        System.out.println("[ReplicationService] Replica node for " + filename
                + " → id=" + replicaNode.getId() + " ip=" + replicaNode.getIp());

        // If the replica node is ourselves → create a self-log, no TCP needed
        if (replicaNode.getId() == state.getCurrentId()) {
            System.out.println("[ReplicationService] Replica is self → local log only.");
            FileLog log = new FileLog(filename, state.getIp(), state.getIp());
            fileLogService.save(log);
            return;
        }

        // Send the file over TCP
        Path filePath = Paths.get(localDir, filename);
        if (!Files.exists(filePath)) {
            System.err.println("[ReplicationService] File not found: " + filePath);
            return;
        }

        tcpClient.sendFile(filePath, replicaNode.getIp());
    }

    private static class ReplicaRequestBody {
        public String filename;
        public ReplicaRequestBody(String f) { this.filename = f; }
        public String getFilename() { return filename; }
    }
}
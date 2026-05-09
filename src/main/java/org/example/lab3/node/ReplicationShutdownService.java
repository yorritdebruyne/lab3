package org.example.lab3.node;

import org.example.lab3.model.FileOwnerResponse;
import org.example.lab3.model.NeighbourResponse;
import org.example.lab3.model.NodeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * ReplicationShutdownService handles file transfer when this node shuts down.
 *
 * Called from ShutdownService.shutdown() BEFORE the ring pointer updates.
 *
 * === Algorithm (Phase: Shutdown from slides) ===
 *
 * For each file in replicas/ (files we are the owner of):
 *   1. Find the previous node in the ring
 *   2. Get the previous node's local file list
 *   3. If prev already has this file locally → go to prev.prev (edge case)
 *   4. Transfer the replica file to the chosen target node over TCP
 *
 * === Port handling ===
 *
 * NodeIpLookup now returns "ip:port" (e.g. "127.0.0.1:8082") instead of
 * just the IP. All URL construction in this class uses the returned address
 * directly without appending a port manually. This fixes localhost
 * multi-node testing where each node has a different port.
 *
 * For NodeInfo-based URLs (owner lookups), we use owner.getIp() + ":" +
 * owner.getPort() since NodeInfo now stores the port.
 */
@Service
@Profile("node")
public class ReplicationShutdownService {

    @Value("${node.replicas.dir:replicas}")
    private String replicasDir;

    @Value("${node.local.dir:local}")
    private String localDir;

    @Value("${namingserver.url}")
    private String namingServerUrl;

    private final NodeState      state;
    private final NodeIpLookup   ipLookup;
    private final TcpFileClient  tcpClient;
    private final FileLogService fileLogService;
    private final RestTemplate   restTemplate = new RestTemplate();

    public ReplicationShutdownService(NodeState state, NodeIpLookup ipLookup,
                                      TcpFileClient tcpClient,
                                      FileLogService fileLogService) {
        this.state          = state;
        this.ipLookup       = ipLookup;
        this.tcpClient      = tcpClient;
        this.fileLogService = fileLogService;
    }

    /**
     * Call this from ShutdownService.shutdown() BEFORE updating ring pointers.
     */
    public void transferReplicasOnShutdown() {
        System.out.println("[ReplicationShutdown] Starting file transfer...");

        List<Path> replicaFiles = listFiles(replicasDir);
        if (replicaFiles.isEmpty()) {
            System.out.println("[ReplicationShutdown] No replica files to transfer.");
            transferLocalFileNotifications();
            return;
        }

        // ipLookup returns "ip:port" — use directly in URLs, no port appended manually
        String prevAddr = ipLookup.getIpForId(state.getPrevId());
        if (prevAddr == null) {
            System.err.println("[ReplicationShutdown] Cannot find prev node IP.");
            return;
        }

        // Extract just the IP for TcpFileClient (TCP uses its own port)
        String prevIp = prevAddr.contains(":") ? prevAddr.split(":")[0] : prevAddr;

        List<String> prevLocalFiles = getLocalFilesOf(prevAddr);

        for (Path replicaFile : replicaFiles) {
            String filename  = replicaFile.getFileName().toString();
            String targetIp  = prevIp;

            if (prevLocalFiles.contains(filename)) {
                // Edge case: prev already has this file locally → go to prev.prev
                System.out.println("[ReplicationShutdown] Edge case: prev has "
                        + filename + " locally → going to prev.prev");
                String prevPrevAddr = getPrevPrevAddr(state.getPrevId());
                if (prevPrevAddr != null) {
                    // Extract IP for TCP
                    targetIp = prevPrevAddr.contains(":")
                            ? prevPrevAddr.split(":")[0]
                            : prevPrevAddr;
                } else {
                    System.err.println("[ReplicationShutdown] No prev.prev found for: " + filename);
                    continue;
                }
            }

            boolean ok = tcpClient.sendFile(replicaFile, targetIp);
            if (ok) {
                System.out.println("[ReplicationShutdown] Transferred: "
                        + filename + " to " + targetIp);
            }
        }

        transferLocalFileNotifications();
        System.out.println("[ReplicationShutdown] Done.");
    }

    /**
     * Notifies the owner of each local file that our node is shutting down.
     *
     * Uses owner.getIp() + ":" + owner.getPort() to build the URL because
     * the owner comes from a NodeInfo object (not from NodeIpLookup).
     */
    private void transferLocalFileNotifications() {
        for (Path localFile : listFiles(localDir)) {
            String filename = localFile.getFileName().toString();
            try {
                FileOwnerResponse owner = restTemplate.getForObject(
                //NodeInfo owner = restTemplate.getForObject(
                        namingServerUrl + "/api/files/owner?filename=" + filename,
                        //NodeInfo.class
                        FileOwnerResponse.class
                );
                if (owner != null && owner.getNodeId() != state.getCurrentId()) {
                    // Use owner.getIp() + ":" + owner.getPort() — correct port per node
                    restTemplate.delete("http://" + owner.getIp() + ":" + owner.getPort()
                            + "/node/replica/" + filename);
                    System.out.println("[ReplicationShutdown] Notified owner "
                            + owner.getIp() + ":" + owner.getPort() + " about: " + filename);
                }
            } catch (Exception e) {
                System.err.println("[ReplicationShutdown] Could not notify for: "
                        + filename + ": " + e.getMessage());
            }
        }
    }

    /**
     * Gets the local file list of a remote node via GET /node/files.
     *
     * @param addr "ip:port" address of the target node (from NodeIpLookup)
     */
    private List<String> getLocalFilesOf(String addr) {
        try {
            // addr is already "ip:port" — use directly, no port appended
            String[] files = restTemplate.getForObject(
                    "http://" + addr + "/node/files",
                    String[].class
            );
            return files != null ? List.of(files) : List.of();
        } catch (Exception e) {
            System.err.println("[ReplicationShutdown] Could not get files from "
                    + addr + ": " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Gets the "ip:port" address of prev.prev by asking the naming server
     * for prev's neighbours, then looking up the prev.prev node.
     */
    private String getPrevPrevAddr(int prevId) {
        try {
            NeighbourResponse neighbours = restTemplate.getForObject(
                    namingServerUrl + "/api/nodes/neighbours/" + prevId,
                    NeighbourResponse.class
            );
            if (neighbours == null) return null;
            return ipLookup.getIpForId(neighbours.getPrevId());
        } catch (Exception e) {
            System.err.println("[ReplicationShutdown] Could not get prev.prev: " + e.getMessage());
            return null;
        }
    }

    private List<Path> listFiles(String dir) {
        try {
            Files.createDirectories(Paths.get(dir));
            try (Stream<Path> stream = Files.list(Paths.get(dir))) {
                return stream.filter(Files::isRegularFile).collect(Collectors.toList());
            }
        } catch (IOException e) {
            return List.of();
        }
    }
}
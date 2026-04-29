package org.example.lab3.node;

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
 * For each file in local/ (our own local files):
 *   1. Notify the owner node that our download location is going away
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

    @Value("${server.port:8081}")
    private int serverPort;

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

        String prevIp = ipLookup.getIpForId(state.getPrevId());
        if (prevIp == null) {
            System.err.println("[ReplicationShutdown] Cannot find prev node IP.");
            return;
        }

        List<String> prevLocalFiles = getLocalFilesOf(prevIp);

        for (Path replicaFile : replicaFiles) {
            String filename = replicaFile.getFileName().toString();
            String targetIp = prevIp;

            if (prevLocalFiles.contains(filename)) {
                // Edge case: prev already has this file locally → go to prev.prev
                System.out.println("[ReplicationShutdown] Edge case: prev has "
                        + filename + " locally → going to prev.prev");
                String prevPrevIp = getPrevPrevIp(state.getPrevId());
                if (prevPrevIp != null) {
                    targetIp = prevPrevIp;
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
     */
    private void transferLocalFileNotifications() {
        for (Path localFile : listFiles(localDir)) {
            String filename = localFile.getFileName().toString();
            try {
                NodeInfo owner = restTemplate.getForObject(
                        namingServerUrl + "/api/files/owner?filename=" + filename,
                        NodeInfo.class
                );
                if (owner != null && owner.getId() != state.getCurrentId()) {
                    restTemplate.delete("http://" + owner.getIp() + ":" + serverPort
                            + "/node/replica/" + filename);
                    System.out.println("[ReplicationShutdown] Notified owner "
                            + owner.getIp() + " about: " + filename);
                }
            } catch (Exception e) {
                System.err.println("[ReplicationShutdown] Could not notify for: "
                        + filename + ": " + e.getMessage());
            }
        }
    }

    /** Gets the local file list of a remote node via GET /node/files */
    private List<String> getLocalFilesOf(String ip) {
        try {
            String[] files = restTemplate.getForObject(
                    "http://" + ip + ":" + serverPort + "/node/files",
                    String[].class
            );
            return files != null ? List.of(files) : List.of();
        } catch (Exception e) {
            System.err.println("[ReplicationShutdown] Could not get files from "
                    + ip + ": " + e.getMessage());
            return List.of();
        }
    }

    /** Gets the IP of prev.prev by asking the naming server for prev's neighbours */
    private String getPrevPrevIp(int prevId) {
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
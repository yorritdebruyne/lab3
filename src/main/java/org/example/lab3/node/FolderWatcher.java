package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * FolderWatcher monitors the local/ folder for file changes at regular intervals.
 *
 * Implements Phase: Update from the slides:
 *   "When a local file is added, it should be replicated immediately."
 *   "If deleted, it has to be deleted from the replicated files of the file owner."
 *
 * Strategy: maintain a snapshot of known filenames.
 *   - New file (in current scan, not in snapshot) → replicate it
 *   - Deleted file (in snapshot, not in current scan) → notify owner to remove replica
 */
@Service
@Profile("node")
public class FolderWatcher {

    @Value("${node.local.dir:local}")
    private String localDir;

    @Value("${node.watch.interval.ms:5000}")
    private long watchInterval;

    @Value("${namingserver.url}")
    private String namingServerUrl;

    @Value("${server.port:8081}")
    private int serverPort;

    private final ReplicationService replicationService;
    private final NodeState          state;
    private final RestTemplate       restTemplate = new RestTemplate();

    // Snapshot of file names known from the last poll
    private final Set<String> knownFiles = new HashSet<>();

    public FolderWatcher(ReplicationService replicationService, NodeState state) {
        this.replicationService = replicationService;
        this.state              = state;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWatching() {
        Thread t = new Thread(this::watchLoop, "folder-watcher");
        t.setDaemon(true);
        t.start();
    }

    private void watchLoop() {
        // Give FileScanner time to finish first
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

        try {
            knownFiles.addAll(listFiles());
        } catch (IOException e) {
            System.err.println("[FolderWatcher] Could not read initial file list: " + e.getMessage());
        }

        System.out.println("[FolderWatcher] Watching: " + localDir
                + " every " + watchInterval + "ms");

        while (true) {
            try {
                Thread.sleep(watchInterval);
                checkForChanges();
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                System.err.println("[FolderWatcher] Error: " + e.getMessage());
            }
        }
    }

    private void checkForChanges() throws IOException {
        Set<String> current = listFiles();

        // Added = in current but not in known
        Set<String> added = new HashSet<>(current);
        added.removeAll(knownFiles);

        // Deleted = in known but not in current
        Set<String> deleted = new HashSet<>(knownFiles);
        deleted.removeAll(current);

        for (String filename : added) {
            System.out.println("[FolderWatcher] New file: " + filename);
            replicationService.replicate(filename);
        }

        for (String filename : deleted) {
            System.out.println("[FolderWatcher] Deleted file: " + filename);
            notifyOwnerOfDeletion(filename);
        }

        knownFiles.clear();
        knownFiles.addAll(current);
    }

    /**
     * Tells the owner node to delete its replica of the given file.
     */
    private void notifyOwnerOfDeletion(String filename) {
        try {
            NodeInfo owner = restTemplate.getForObject(
                    namingServerUrl + "/api/files/owner?filename=" + filename,
                    NodeInfo.class
            );

            if (owner == null) return;

            // If we are the owner ourselves, nothing to do remotely
            if (owner.getId() == state.getCurrentId()) return;

            restTemplate.delete("http://" + owner.getIp() + ":" + serverPort
                    + "/node/replica/" + filename);
            System.out.println("[FolderWatcher] Notified owner " + owner.getIp()
                    + " to delete replica of: " + filename);

        } catch (Exception e) {
            System.err.println("[FolderWatcher] Could not notify owner for: "
                    + filename + ": " + e.getMessage());
        }
    }

    private Set<String> listFiles() throws IOException {
        Files.createDirectories(Paths.get(localDir));
        try (Stream<Path> stream = Files.list(Paths.get(localDir))) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }
}
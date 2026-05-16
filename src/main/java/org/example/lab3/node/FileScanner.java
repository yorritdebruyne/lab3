package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

/**
 * FileScanner runs once after the ring has formed (not just after startup).
 *
 * It scans the node's local/ folder for any existing files and triggers
 * ReplicationService for each one.
 *
 * Implements Phase: Starting from the slides:
 *   "After bootstrap and discovery, the new node has to verify its local files
 *    and for all files, hash values are calculated and reported to the naming server."
 *
 * @Order(2) ensures this runs AFTER BootstrapService so the node is already
 * registered before we try to replicate.
 */
@Service
@Profile("node")
@Order(2)
public class FileScanner {

    @Value("${node.local.dir:local}")
    private String localDir;

    private final ReplicationService replicationService;
    private final NodeState          state;

    public FileScanner(ReplicationService replicationService, NodeState state) {
        this.replicationService = replicationService;
        this.state              = state;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void scanAndReplicate() {
        Thread t = new Thread(this::doScanAndReplicate, "file-scanner");
        t.setDaemon(true);
        t.start();
    }

    private void doScanAndReplicate() {
        // Poll until the ring has formed (next neighbour is known) or 30 s elapses.
        // Running in a background thread so TcpFileServer and MulticastReceiver
        // can start immediately and the ring can actually form while we wait.
        int waited = 0;
        while (state.getNextId() == state.getCurrentId() && waited < 30) {
            try { Thread.sleep(1_000); } catch (InterruptedException ignored) { break; }
            waited++;
        }
        if (state.getNextId() != state.getCurrentId()) {
            System.out.println("[FileScanner] Ring formed after " + waited + "s, scanning.");
        } else {
            System.out.println("[FileScanner] Ring not formed after 30s, scanning anyway.");
        }

        System.out.println("[FileScanner] Scanning: " + localDir);

        try {
            Files.createDirectories(Paths.get(localDir));
        } catch (IOException e) {
            System.err.println("[FileScanner] Could not create local dir: " + e.getMessage());
            return;
        }

        try (Stream<Path> files = Files.list(Paths.get(localDir))) {
            files
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .forEach(filename -> {
                        System.out.println("[FileScanner] Found: " + filename);
                        replicationService.replicate(filename);
                    });
        } catch (IOException e) {
            System.err.println("[FileScanner] Error scanning: " + e.getMessage());
        }

        System.out.println("[FileScanner] Done.");
    }
}
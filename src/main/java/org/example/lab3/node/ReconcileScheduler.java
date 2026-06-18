package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * ReconcileScheduler — periodic anti-entropy / garbage-collection sweep.
 *
 * Real distributed stores run a background sweep so each node converges to
 * holding exactly the data it owns. We do the same: every interval, the node
 * re-checks the replicas in its replicas/ folder and hands off (then deletes)
 * any it no longer owns. So a node's replicas/ folder self-cleans to exactly
 * the files that hash to it — even after churn, leftover state, or a restart
 * with pre-existing files.
 *
 * It REUSES the existing, safe redistribution pass (transfer-to-owner THEN
 * delete locally — never deletes before the owner has the file), so it adds no
 * new placement logic. Runs on its own daemon thread, matching the project's
 * other background workers (FolderWatcher, MulticastReceiver, TcpFileServer).
 */
@Service
@Profile("node")
public class ReconcileScheduler {

    @Value("${node.reconcile.interval.ms:10000}")
    private long intervalMs;

    private final NodeJoinRedistributionService redistribution;
    private final ReplicationService replication;

    public ReconcileScheduler(NodeJoinRedistributionService redistribution,
                              ReplicationService replication) {
        this.redistribution = redistribution;
        this.replication = replication;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread t = new Thread(this::loop, "reconcile-scheduler");
        t.setDaemon(true);
        t.start();
        System.out.println("[ReconcileScheduler] Started (every " + intervalMs + " ms).");
    }

    private void loop() {
        while (true) {
            try {
                Thread.sleep(intervalMs);

                // Pass 1 — replica cleanup. Reuses the join-redistribution pass: for
                // each replica we hold, if we are no longer the owner, transfer it to
                // the owner and drop our copy. The AtomicBoolean inside coalesces with
                // join-triggered runs.
                redistribution.onNextNeighbourChanged();

                // Pass 2 — replica self-heal. Re-push our local/ files to their CURRENT
                // owner whenever ownership has changed (e.g. the previous owner crashed,
                // so the file now hashes to a different node). Restores replicas that a
                // crash would otherwise leave orphaned. No-op when nothing changed.
                replication.replicateAllLocal();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("[ReconcileScheduler] sweep failed: " + e.getMessage());
            }
        }
    }
}

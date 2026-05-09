package org.example.lab3.node;

import org.example.lab3.node.NodeController.NeighbourUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PreDestroy;

/**
 * ShutdownService handles a GRACEFUL shutdown of this node.
 *
 * Spring calls shutdown() automatically when the application stops —
 * triggered by CTRL-C, SIGTERM, or SpringApplication.exit().
 * The @PreDestroy annotation is what makes Spring call it.
 *
 * === What we do (from the slides, Shutdown section) ===
 *
 * 1. Tell the PREVIOUS node: "your new next is my current next."
 *    PUT http://{prevAddr}/node/next  { id: this.nextId }
 *
 * 2. Tell the NEXT node: "your new prev is my current prev."
 *    PUT http://{nextAddr}/node/prev  { id: this.prevId }
 *
 * 3. Remove ourselves from the naming server's ring map.
 *    DELETE /api/nodes/{name}
 *
 * After these three steps the ring is intact again without us.
 *
 * Note: if we are the only node (prev == next == self), steps 1 and 2
 * are skipped because there are no neighbours to notify.
 *
 * === Port handling ===
 *
 * NodeIpLookup now returns "ip:port" (e.g. "127.0.0.1:8082") instead of
 * just the IP. This means ShutdownService no longer needs its own peerPort
 * field — the correct port for each neighbour comes from the naming server
 * via NodeIpLookup. This fixes the localhost multi-node testing issue where
 * all nodes share 127.0.0.1 but use different ports.
 */
@Service
@Profile("node")
public class ShutdownService {

    @Value("${namingserver.url}")
    private String namingServerUrl;

    private final NodeState                  state;
    private final NodeIpLookup               ipLookup;
    private final ReplicationShutdownService replicationShutdownService;
    private final RestTemplate               restTemplate = new RestTemplate();

    public ShutdownService(NodeState state, NodeIpLookup ipLookup,
                           ReplicationShutdownService replicationShutdownService) {
        this.state                      = state;
        this.ipLookup                   = ipLookup;
        this.replicationShutdownService = replicationShutdownService;
    }

    @PreDestroy
    public void shutdown() {
        // Lab 5: transfer replica files to prev before disconnecting
        replicationShutdownService.transferReplicasOnShutdown();

        System.out.println("[Shutdown] Starting graceful shutdown. State: " + state);

        int myId   = state.getCurrentId();
        int prevId = state.getPrevId();
        int nextId = state.getNextId();

        boolean alone = (prevId == myId && nextId == myId);

        if (!alone) {

            // --- Step 1: tell prev its new next is our current next ---
            // ipLookup returns "ip:port" so we can build the URL directly
            String prevAddr = ipLookup.getIpForId(prevId);
            if (prevAddr != null) {
                try {
                    NeighbourUpdate req = new NeighbourUpdate();
                    req.setId(nextId);
                    restTemplate.put("http://" + prevAddr + "/node/next", req);
                    System.out.println("[Shutdown] Told prev (" + prevId + ") new next = " + nextId);
                } catch (Exception e) {
                    System.err.println("[Shutdown] Could not reach prev: " + e.getMessage());
                }
            }

            // --- Step 2: tell next its new prev is our current prev ---
            String nextAddr = ipLookup.getIpForId(nextId);
            if (nextAddr != null) {
                try {
                    NeighbourUpdate req = new NeighbourUpdate();
                    req.setId(prevId);
                    restTemplate.put("http://" + nextAddr + "/node/prev", req);
                    System.out.println("[Shutdown] Told next (" + nextId + ") new prev = " + prevId);
                } catch (Exception e) {
                    System.err.println("[Shutdown] Could not reach next: " + e.getMessage());
                }
            }
        }

        // --- Step 3: remove ourselves from the naming server ---
        try {
            restTemplate.delete(namingServerUrl + "/api/nodes/" + state.getName());
            System.out.println("[Shutdown] Removed from naming server.");
        } catch (Exception e) {
            System.err.println("[Shutdown] Could not remove from naming server: " + e.getMessage());
        }

        System.out.println("[Shutdown] Done.");
    }
}
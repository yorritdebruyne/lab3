package org.example.lab3.node;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * AgentController exposes POST /agent/receive — the single endpoint
 * that all agents use to arrive at a node.
 *
 * When a payload arrives:
 *   1. Deserialize the JSON into an AgentPayload (Spring does this automatically)
 *   2. Look at payload.type to decide which agent logic to run
 *   3. Run the logic (SyncAgent merge OR FailureAgent execute)
 *   4. Decide whether to forward to the next node
 *
 * === Forwarding rules ===
 *
 * SYNC:
 *   Never forward — each node has its own SyncAgent that pushes outward.
 *   We just merge the incoming file list into our FileRegistry and stop.
 *
 * FAILURE:
 *   Always forward to the next node UNLESS this node's ID equals
 *   payload.startNodeId (meaning the agent has completed the full ring).
 *
 * The forwarding is done asynchronously in a new thread so this
 * endpoint returns immediately (HTTP 200) without waiting for the
 * entire ring traversal to complete.
 */
@RestController
@RequestMapping("/agent")
@Profile("node")
public class AgentController {

    private final NodeState       state;
    private final FileRegistry    fileRegistry;
    private final FailureAgent    failureAgent;
    private final AgentDispatcher dispatcher;
    private final NodeIpLookup    ipLookup;

    public AgentController(NodeState state, FileRegistry fileRegistry,
                           FailureAgent failureAgent, AgentDispatcher dispatcher,
                           NodeIpLookup ipLookup) {
        this.state        = state;
        this.fileRegistry = fileRegistry;
        this.failureAgent = failureAgent;
        this.dispatcher   = dispatcher;
        this.ipLookup     = ipLookup;
    }

    /**
     * Receives an agent payload, runs the appropriate logic,
     * and forwards it if needed.
     *
     * Returns HTTP 200 immediately — the forwarding happens in a
     * background thread so the caller doesn't have to wait.
     *
     * @param payload The incoming agent payload as JSON.
     */
    @PostMapping("/receive")
    public ResponseEntity<Void> receiveAgent(@RequestBody AgentPayload payload) {
        System.out.println("[AgentController] Received " + payload.getType()
                + " agent on node " + state.getCurrentId());

        // Run logic and optional forwarding in a background thread
        // so we return HTTP 200 immediately to the sender
        new Thread(() -> handleAgent(payload), "agent-handler").start();

        return ResponseEntity.ok().build();
    }

    /**
     * Handles the agent logic based on its type.
     */
    private void handleAgent(AgentPayload payload) {
        switch (payload.getType()) {

            case "SYNC":
                handleSync(payload);
                // SYNC agents are never forwarded — each node has its own SyncAgent
                break;

            case "FAILURE":
                handleFailure(payload);
                break;

            default:
                System.err.println("[AgentController] Unknown agent type: " + payload.getType());
        }
    }

    /**
     * Handles a SYNC payload:
     *   Merge the incoming file list into our own FileRegistry.
     *   No forwarding — each node's own SyncAgent handles pushing outward.
     */
    private void handleSync(AgentPayload payload) {
        if (payload.getFileList() != null && !payload.getFileList().isEmpty()) {
            fileRegistry.merge(payload.getFileList());
            System.out.println("[AgentController] SYNC: merged "
                    + payload.getFileList().size() + " entries into FileRegistry.");
        }
    }

    /**
     * Handles a FAILURE payload:
     *   1. Run FailureAgent logic on this node (check + fix file ownership)
     *   2. If this node is NOT the start node → forward to next node
     *   3. If this node IS the start node → terminate (full ring traversal done)
     */
    private void handleFailure(AgentPayload payload) {
        int myId  = state.getCurrentId();
        int hops  = payload.getHopCount();

        // Termination: if we have already been visited, the ring is fully traversed → stop.
        // We do NOT run execute() again on a second visit — this node already recovered.
        if (payload.getVisitedNodeIds().contains(myId)) {
            System.out.println("[AgentController] FAILURE agent completed ring at node "
                    + myId + " (visited=" + payload.getVisitedNodeIds() + ") — terminating.");
            return;
        }

        // Safety net: stop after MAX_HOPS regardless, in case visited list is corrupted
        if (hops >= AgentPayload.MAX_HOPS) {
            System.err.println("[AgentController] FAILURE agent exceeded " + AgentPayload.MAX_HOPS
                    + " hops — terminating to prevent infinite loop.");
            return;
        }

        // Step 1: run the failure recovery logic on this node
        failureAgent.execute(payload);

        // Mark this node as visited
        payload.getVisitedNodeIds().add(myId);
        payload.setHopCount(hops + 1);

        System.out.println("[AgentController] FAILURE hop " + (hops + 1)
                + ": node " + myId + " done, visited=" + payload.getVisitedNodeIds());

        // Step 2: find next node
        int nextId = state.getNextId();

        if (nextId == myId || nextId == -1) {
            System.out.println("[AgentController] FAILURE agent: no next node — stopping.");
            return;
        }

        String nextIp = ipLookup.getIpForId(nextId);
        if (nextIp == null) {
            System.err.println("[AgentController] FAILURE agent: cannot find next node IP.");
            return;
        }

        System.out.println("[AgentController] Forwarding FAILURE agent to next node (id=" + nextId + ")");
        dispatcher.dispatch(nextIp, payload);
    }
}
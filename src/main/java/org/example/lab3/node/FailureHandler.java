package org.example.lab3.node;

import org.example.lab3.model.NeighbourResponse;
import org.example.lab3.node.NodeController.NeighbourUpdate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * FailureHandler repairs the ring after a node crash is detected.
 *
 * This is called by PingScheduler when a ping to a neighbour fails.
 *
 * === Algorithm (from the slides, Failure section) ===
 *
 * 1. Ask the naming server for the dead node's neighbours:
 *    GET /api/nodes/neighbours/{deadId}
 *    → returns { prevId, nextId }  (the dead node's prev and next)
 *
 * 2. Tell the PREVIOUS node of the dead node to update its next pointer:
 *    PUT http://{prevIp}/node/next  { id: deadNode.nextId }
 *    → prev.next now skips over the dead node
 *
 * 3. Tell the NEXT node of the dead node to update its prev pointer:
 *    PUT http://{nextIp}/node/prev  { id: deadNode.prevId }
 *    → next.prev now skips over the dead node
 *
 * 4. Remove the dead node from the naming server:
 *    DELETE /api/nodes/{deadNodeName}
 *
 * 5. If the dead node was OUR own prev or next, update our local state too.
 *
 * After these steps the ring is intact again, without the dead node.
 */
@Service
@Profile("node")
public class FailureHandler {

    @Value("${namingserver.url}")
    private String namingServerUrl;

    @Value("${node.peer.port:8081}")
    private int peerPort;

    private final NodeState       state;
    private final NodeIpLookup    ipLookup;
    private final AgentDispatcher agentDispatcher; // Lab 6: dispatches the FailureAgent
    private final RestTemplate    restTemplate = new RestTemplate();

    public FailureHandler(NodeState state, NodeIpLookup ipLookup,
                          AgentDispatcher agentDispatcher) {
        this.state           = state;
        this.ipLookup        = ipLookup;
        this.agentDispatcher = agentDispatcher;
    }
    /**
     * Call this whenever a REST call to a neighbour throws an exception.
     *
     * @param deadNodeId    Ring ID of the node that appears dead.
     * @param deadNodeName  Name of the dead node (needed for DELETE on naming server).
     *                      If you don't have the name, pass null — the DELETE will be skipped.
     */
    public void handleFailure(int deadNodeId, String deadNodeName) {
        System.out.println("[FailureHandler] Handling failure of node id=" + deadNodeId);

        // --- Step 1: ask the naming server for the dead node's neighbours ---
        NeighbourResponse neighbours;
        try {
            neighbours = restTemplate.getForObject(
                    namingServerUrl + "/api/nodes/neighbours/" + deadNodeId,
                    NeighbourResponse.class
            );
        } catch (Exception e) {
            System.err.println("[FailureHandler] Could not reach naming server: " + e.getMessage());
            return; // cannot repair without the naming server
        }

        if (neighbours == null) {
            System.err.println("[FailureHandler] No neighbours returned for id=" + deadNodeId);
            return;
        }

        int deadPrevId = neighbours.getPrevId();
        int deadNextId = neighbours.getNextId();

        System.out.println("[FailureHandler] Dead node's prev=" + deadPrevId
                + "  next=" + deadNextId);

        // --- Step 2: tell the dead node's PREV to point its next at dead node's next ---
        String prevIp = ipLookup.getIpForId(deadPrevId);
        if (prevIp != null) {
            try {
                NeighbourUpdate req = new NeighbourUpdate();
                req.setId(deadNextId); // prev.next = dead.next
                restTemplate.put("http://" + prevIp + ":" + peerPort + "/node/next", req);
                System.out.println("[FailureHandler] Updated prev node (" + deadPrevId
                        + ") next → " + deadNextId);
            } catch (Exception e) {
                System.err.println("[FailureHandler] Could not update prev node: " + e.getMessage());
            }
        }

        // --- Step 3: tell the dead node's NEXT to point its prev at dead node's prev ---
        String nextIp = ipLookup.getIpForId(deadNextId);
        if (nextIp != null) {
            try {
                NeighbourUpdate req = new NeighbourUpdate();
                req.setId(deadPrevId); // next.prev = dead.prev
                restTemplate.put("http://" + nextIp + ":" + peerPort + "/node/prev", req);
                System.out.println("[FailureHandler] Updated next node (" + deadNextId
                        + ") prev → " + deadPrevId);
            } catch (Exception e) {
                System.err.println("[FailureHandler] Could not update next node: " + e.getMessage());
            }
        }

        // --- Step 4: remove the dead node from the naming server ---
        if (deadNodeName != null) {
            try {
                restTemplate.delete(namingServerUrl + "/api/nodes/" + deadNodeName);
                System.out.println("[FailureHandler] Removed " + deadNodeName + " from naming server.");
            } catch (Exception e) {
                System.err.println("[FailureHandler] Could not remove from naming server: " + e.getMessage());
            }
        }

        // --- Step 5: update OUR OWN state if the dead node was our neighbour ---
        if (state.getPrevId() == deadNodeId) {
            state.setPrevId(deadPrevId);
            System.out.println("[FailureHandler] Updated own prev to " + deadPrevId);
        }
        if (state.getNextId() == deadNodeId) {
            state.setNextId(deadNextId);
            System.out.println("[FailureHandler] Updated own next to " + deadNextId);
        }

        // --- Step 6 (Lab 6): launch the FailureAgent to recover file ownership ---
        // The ring pointers are now correct, so the agent can navigate safely.
        // We send the agent to OUR OWN next node (the updated pointer).
        // The agent carries:
        //   failingNodeId = deadNodeId   (so each node knows which files to reassign)
        //   startNodeId   = our own ID  (so the agent knows when it has done a full loop)
        launchFailureAgent(deadNodeId);
    }

    /**
     * Creates a FAILURE AgentPayload and dispatches it to the next node.
     *
     * The agent will travel the entire ring. On each node it visits,
     * it checks whether any replica files were owned by the dead node
     * and transfers ownership accordingly. When the agent arrives back
     * at THIS node (startNodeId == currentNodeId), it terminates.
     *
     * We skip launching if we are alone in the ring or if the next
     * node's IP cannot be found.
     *
     * @param deadNodeId The ring ID of the crashed node.
     */
    private void launchFailureAgent(int deadNodeId) {
        int myId   = state.getCurrentId();
        int nextId = state.getNextId();

        // Don't launch if we are alone in the ring
        if (nextId == myId || nextId == -1) {
            System.out.println("[FailureHandler] Alone in ring — FailureAgent not launched.");
            return;
        }

        String nextIp = ipLookup.getIpForId(nextId);
        if (nextIp == null) {
            System.err.println("[FailureHandler] Cannot find next node IP — "
                    + "FailureAgent not launched.");
            return;
        }

        // Build the payload:
        //   failingNodeId = the dead node (so every node knows whose files to reassign)
        //   startNodeId   = us (so the agent terminates when it comes back here)
        AgentPayload payload = AgentPayload.forFailure(deadNodeId, myId);

        agentDispatcher.dispatch(nextIp, payload);
        System.out.println("[FailureHandler] FailureAgent launched toward node "
                + nextId + " (ip=" + nextIp + ")");
    }
}
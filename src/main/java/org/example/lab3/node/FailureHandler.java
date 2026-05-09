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
 * Called by PingScheduler when a ping to a neighbour fails.
 *
 * === Algorithm ===
 *
 * 1. Ask the naming server for the dead node's neighbours:
 *    GET /api/nodes/neighbours/{deadId} → { prevId, nextId }
 *
 * 2. Tell the dead node's PREV to update its next pointer:
 *    PUT http://{prevAddr}/node/next  { id: deadNode.nextId }
 *
 * 3. Tell the dead node's NEXT to update its prev pointer:
 *    PUT http://{nextAddr}/node/prev  { id: deadNode.prevId }
 *
 * 4. Remove the dead node from the naming server.
 *
 * 5. Update OUR OWN state if the dead node was our prev or next.
 *
 * 6. (Lab 6) Launch the FailureAgent to recover file ownership.
 *
 * === Port handling ===
 *
 * NodeIpLookup returns "ip:port" so this class no longer needs its own
 * peerPort field. The correct port for each neighbour comes from the
 * naming server, fixing localhost multi-node testing.
 */
@Service
@Profile("node")
public class FailureHandler {

    @Value("${namingserver.url}")
    private String namingServerUrl;

    private final NodeState       state;
    private final NodeIpLookup    ipLookup;
    private final AgentDispatcher agentDispatcher;
    private final RestTemplate    restTemplate = new RestTemplate();

    public FailureHandler(NodeState state, NodeIpLookup ipLookup,
                          AgentDispatcher agentDispatcher) {
        this.state           = state;
        this.ipLookup        = ipLookup;
        this.agentDispatcher = agentDispatcher;
    }

    public void handleFailure(int deadNodeId, String deadNodeName) {
        System.out.println("[FailureHandler] Handling failure of node id=" + deadNodeId);

        // Step 1: get dead node's neighbours from naming server
        NeighbourResponse neighbours;
        try {
            neighbours = restTemplate.getForObject(
                    namingServerUrl + "/api/nodes/neighbours/" + deadNodeId,
                    NeighbourResponse.class
            );
        } catch (Exception e) {
            System.err.println("[FailureHandler] Could not reach naming server: " + e.getMessage());
            return;
        }

        if (neighbours == null) {
            System.err.println("[FailureHandler] No neighbours returned for id=" + deadNodeId);
            return;
        }

        int deadPrevId = neighbours.getPrevId();
        int deadNextId = neighbours.getNextId();

        System.out.println("[FailureHandler] Dead node's prev=" + deadPrevId
                + "  next=" + deadNextId);

        // Step 2: tell dead node's PREV its new next
        // ipLookup returns "ip:port" — use directly in URL
        String prevAddr = ipLookup.getIpForId(deadPrevId);
        if (prevAddr != null) {
            try {
                NeighbourUpdate req = new NeighbourUpdate();
                req.setId(deadNextId);
                restTemplate.put("http://" + prevAddr + "/node/next", req);
                System.out.println("[FailureHandler] Updated prev node ("
                        + deadPrevId + ") next → " + deadNextId);
            } catch (Exception e) {
                System.err.println("[FailureHandler] Could not update prev node: " + e.getMessage());
            }
        }

        // Step 3: tell dead node's NEXT its new prev
        String nextAddr = ipLookup.getIpForId(deadNextId);
        if (nextAddr != null) {
            try {
                NeighbourUpdate req = new NeighbourUpdate();
                req.setId(deadPrevId);
                restTemplate.put("http://" + nextAddr + "/node/prev", req);
                System.out.println("[FailureHandler] Updated next node ("
                        + deadNextId + ") prev → " + deadPrevId);
            } catch (Exception e) {
                System.err.println("[FailureHandler] Could not update next node: " + e.getMessage());
            }
        }

        // Step 4: remove dead node from naming server
        if (deadNodeName != null) {
            try {
                restTemplate.delete(namingServerUrl + "/api/nodes/" + deadNodeName);
                System.out.println("[FailureHandler] Removed " + deadNodeName
                        + " from naming server.");
            } catch (Exception e) {
                System.err.println("[FailureHandler] Could not remove from naming server: "
                        + e.getMessage());
            }
        }

        // Step 5: update OUR OWN state if dead node was our neighbour
        if (state.getPrevId() == deadNodeId) {
            state.setPrevId(deadPrevId);
            System.out.println("[FailureHandler] Updated own prev to " + deadPrevId);
        }
        if (state.getNextId() == deadNodeId) {
            state.setNextId(deadNextId);
            System.out.println("[FailureHandler] Updated own next to " + deadNextId);
        }

        // Step 6 (Lab 6): launch the FailureAgent to recover file ownership
        launchFailureAgent(deadNodeId);
    }

    private void launchFailureAgent(int deadNodeId) {
        int myId   = state.getCurrentId();
        int nextId = state.getNextId();

        if (nextId == myId || nextId == -1) {
            System.out.println("[FailureHandler] Alone in ring — FailureAgent not launched.");
            return;
        }

        String nextAddr = ipLookup.getIpForId(nextId);
        if (nextAddr == null) {
            System.err.println("[FailureHandler] Cannot find next node — FailureAgent not launched.");
            return;
        }

        // Extract just the IP for AgentDispatcher (it builds its own URL with port)
        String nextIp = nextAddr.contains(":") ? nextAddr.split(":")[0] : nextAddr;

        AgentPayload payload = AgentPayload.forFailure(deadNodeId, myId);
        agentDispatcher.dispatch(nextIp, payload);
        System.out.println("[FailureHandler] FailureAgent launched toward node " + nextId);
    }
}
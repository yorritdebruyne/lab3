package org.example.lab3.node;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * AgentDispatcher is responsible for sending an AgentPayload to
 * another node's POST /agent/receive endpoint.
 *
 * Think of it as the "postal service" for agents — it takes a payload
 * and delivers it to the correct address.
 *
 * === Port handling ===
 *
 * The targetAddr parameter is expected to be "ip:port"
 * (e.g. "127.0.0.1:8082") as returned by NodeIpLookup.
 * AgentDispatcher no longer has its own peerPort field — the correct
 * port for each target node comes from NodeIpLookup, fixing the
 * localhost multi-node testing issue where all nodes share 127.0.0.1
 * but use different ports.
 *
 * Used by:
 *   AgentController — after running the agent locally, forward it to next node
 *   FailureHandler  — to launch the FailureAgent onto the next node
 *   SyncAgent       — to push the SYNC payload to the next node
 */
@Service
@Profile("node")
public class AgentDispatcher {

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Sends an AgentPayload to the target node via POST /agent/receive.
     *
     * The receiving node's AgentController will:
     *   1. Deserialize the JSON back into an AgentPayload
     *   2. Run the appropriate agent logic
     *   3. Forward it to the next node (unless termination condition is met)
     *
     * @param targetAddr  "ip:port" address of the target node (from NodeIpLookup)
     * @param payload     The agent payload to send
     * @return true if the send succeeded, false on error
     */
    public boolean dispatch(String targetAddr, AgentPayload payload) {
        // targetAddr is already "ip:port" — use directly in the URL
        String url = "http://" + targetAddr + "/agent/receive";
        try {
            restTemplate.postForObject(url, payload, Void.class);
            System.out.println("[AgentDispatcher] Sent " + payload.getType()
                    + " agent to " + targetAddr);
            return true;
        } catch (Exception e) {
            System.err.println("[AgentDispatcher] Failed to send "
                    + payload.getType() + " agent to " + targetAddr
                    + ": " + e.getMessage());
            return false;
        }
    }
}
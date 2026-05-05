package org.example.lab3.node;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * AgentDispatcher is responsible for sending an AgentPayload to
 * another node's POST /agent/receive endpoint.
 *
 * Think of it as the "postal service" for agents — it takes a payload
 * and delivers it to the correct IP and port.
 *
 * Used by:
 *   AgentController — after running the agent locally, forward it to next node
 *   FailureHandler  — to launch the FailureAgent onto the next node
 *   BootstrapService — to launch the SyncAgent for the first time
 */
@Service
@Profile("node")
public class AgentDispatcher {

    @Value("${node.peer.port:8081}")
    private int peerPort;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Sends an AgentPayload to the target node via POST /agent/receive.
     *
     * The receiving node's AgentController will:
     *   1. Deserialize the JSON back into an AgentPayload
     *   2. Run the appropriate agent logic
     *   3. Forward it to the next node (unless termination condition is met)
     *
     * @param targetIp  IP address of the node to send the agent to
     * @param payload   The agent payload to send
     * @return true if the send succeeded, false on error
     */
    public boolean dispatch(String targetIp, AgentPayload payload) {
        String url = "http://" + targetIp + ":" + peerPort + "/agent/receive";
        try {
            restTemplate.postForObject(url, payload, Void.class);
            System.out.println("[AgentDispatcher] Sent " + payload.getType()
                    + " agent to " + targetIp);
            return true;
        } catch (Exception e) {
            System.err.println("[AgentDispatcher] Failed to send "
                    + payload.getType() + " agent to " + targetIp
                    + ": " + e.getMessage());
            return false;
        }
    }
}

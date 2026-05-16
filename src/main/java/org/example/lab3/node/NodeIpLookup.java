package org.example.lab3.node;

import org.example.lab3.model.NodeInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * NodeIpLookup answers: "Given a ring ID, what is that node's address?"
 *
 * Returns "ip:port" as a single string (e.g. "127.0.0.1:8082") so that
 * callers can build REST URLs directly:
 *
 *   String addr = ipLookup.getIpForId(nextId);
 *   restTemplate.put("http://" + addr + "/node/next", req);
 *
 * Storing and returning the port is essential for localhost testing where
 * multiple nodes share IP 127.0.0.1 but listen on different ports.
 * On a real distributed deployment each node has a unique IP, so the port
 * component is always 8081 — but returning ip:port works correctly in
 * both cases.
 */
@Service
@Profile("node")
public class NodeIpLookup {

    @Value("${namingserver.url}")
    private String namingServerUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Returns "ip:port" for the node with the given ring ID,
     * by asking the naming server GET /api/nodes/{id}.
     * Returns null if the node is not found.
     *
     * @param nodeId  The hash ring ID of the node to look up.
     * @return "ip:port" string, e.g. "127.0.0.1:8082", or null if not found.
     */
    public String getIpForId(int nodeId) {
        NodeInfo info = getNodeForId(nodeId);
        if (info == null) return null;
        return info.getIp() + ":" + info.getPort();
    }

    public NodeInfo getNodeForId(int nodeId) {
        try {
            return restTemplate.getForObject(
                    namingServerUrl + "/api/nodes/" + nodeId,
                    NodeInfo.class
            );
        } catch (Exception e) {
            System.err.println("[NodeIpLookup] Could not find node for id=" + nodeId
                    + ": " + e.getMessage());
            return null;
        }
    }
}
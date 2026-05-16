package org.example.lab3.node;

import org.example.lab3.model.AddNodeRequest;
import org.example.lab3.model.NeighbourResponse;
import org.example.lab3.model.NodeInfo;
import org.example.lab3.service.HashService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * BootstrapService handles the DISCOVERY and BOOTSTRAP phases.
 *
 * It runs once, automatically, after Spring has fully started up.
 *
 * === What it does step by step (matching the slides) ===
 *
 * 1. Calculate our hash ID from our node name using HashService.
 *    Store it in NodeState.currentId.
 *
 * 2. Send a UDP multicast message to the entire local network.
 *    Message format:  "name:ip"   e.g. "nodeA:192.168.0.5"
 *    Everyone on the multicast group receives this:
 *      - The naming server registers us and sends back the node count.
 *      - Other nodes decide if we are their new neighbour (handled in
 *        MulticastReceiver).
 *
 * 3. Wait for the naming server's unicast reply: just a number (the count
 *    of nodes that existed BEFORE we joined).
 *
 *    count == 0  →  we are alone; set prev = next = self.
 *    count > 0   →  there are other nodes; they will call PUT /node/prev
 *                   and PUT /node/next on us via REST to tell us our
 *                   neighbours. We just wait — NodeController handles those.
 */
@Service
@Profile("node")
public class BootstrapService {

    @Value("${multicast.group:230.0.0.0}")
    private String multicastGroup;

    @Value("${multicast.port:4446}")
    private int multicastPort;

    @Value("${node.name}")
    private String nodeName;

    @Value("${node.ip}")
    private String nodeIp;

    @Value("${node.peer.port:8081}")
    private int peerPort;

    @Value("${node.tcp.port:9000}")
    private int tcpPort;

    @Value("${namingserver.url:http://localhost:8080}")
    private String namingUrl;

    private final NodeState    state;
    private final HashService  hashService;
    private final RestTemplate restTemplate = new RestTemplate();

    public BootstrapService(NodeState state, HashService hashService) {
        this.state       = state;
        this.hashService = hashService;
    }

    /**
     * Spring fires ApplicationReadyEvent once the whole context is up.
     * This is the safest place to start network activity — everything is wired.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {

        // --- Step 1: compute and store our own ring ID ---
        int myId = hashService.hashToRing(nodeName);
        state.setCurrentId(myId);
        state.setIp(nodeIp);
        state.setName(nodeName);

        // Default: point to ourselves until neighbours tell us otherwise
        state.setPrevId(myId);
        state.setNextId(myId);

        System.out.println("[Bootstrap] Starting. name=" + nodeName
                + "  id=" + myId + "  ip=" + nodeIp);

        try (MulticastSocket socket = new MulticastSocket(multicastPort)) {

            InetAddress group = InetAddress.getByName(multicastGroup);
            NetworkInterface networkInterface = NetworkInterface.getByIndex(0);
            socket.joinGroup(new InetSocketAddress(group, multicastPort), networkInterface);

            // --- Step 2: broadcast our name and IP ---
            //String  message = nodeName + ":" + nodeIp;
            // Changed from: String message = nodeName + ":" + nodeIp;
            String message = nodeName + ":" + nodeIp + ":" + peerPort + ":" + tcpPort;
            byte[]  data    = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, group, multicastPort);
            socket.send(packet);
            System.out.println("[Bootstrap] Multicast sent: " + message);

            // --- Step 3: wait for the naming server's unicast reply (node count) ---
            // Timeout so we don't block forever if the server is down.
            socket.setSoTimeout(3000);
            byte[]        buf   = new byte[64];
            DatagramPacket reply = new DatagramPacket(buf, buf.length);

            try {
                socket.receive(reply);

                String countStr = new String(reply.getData(), 0, reply.getLength(),
                        StandardCharsets.UTF_8).trim();

                try {
                    int existingCount = Integer.parseInt(countStr);
                    System.out.println("[Bootstrap] Naming server says: "
                            + existingCount + " node(s) existed before us.");
                    if (existingCount < 1) {
                        System.out.println("[Bootstrap] Alone in the network. prev=next=self.");
                    } else {
                        System.out.println("[Bootstrap] Waiting for neighbour updates from existing nodes...");
                    }
                } catch (NumberFormatException nfe) {
                    // Received our own multicast loopback packet — treat same as timeout.
                    System.err.println("[Bootstrap] Loopback packet received, falling back to REST.");
                    registerAndFormRingViaRest();
                }

            } catch (SocketTimeoutException e) {
                // Naming server did not reply in time (reply goes to port 4447, we listen on 4446).
                // Fall back to direct REST registration + REST-based ring formation.
                System.err.println("[Bootstrap] Naming server timed out — falling back to REST registration.");
                registerAndFormRingViaRest();
            }

        } catch (Exception e) {
            System.err.println("[Bootstrap] Error during bootstrap: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[Bootstrap] Done. State: " + state);
    }

    /**
     * Registers this node with the naming server via REST and forms the ring
     * by querying neighbours and sending PUT /node/prev + PUT /node/next.
     *
     * Used as a fallback when UDP multicast is unreliable (Windows loopback).
     */
    private void registerAndFormRingViaRest() {
        int myId = state.getCurrentId();

        // 1. Register this node with the naming server
        try {
            AddNodeRequest req = new AddNodeRequest();
            req.setName(nodeName);
            req.setIp(nodeIp);
            req.setPort(peerPort);
            req.setTcpPort(tcpPort);
            restTemplate.postForObject(namingUrl + "/api/nodes", req, NodeInfo.class);
            System.out.println("[Bootstrap] Registered via REST fallback.");
        } catch (Exception e) {
            System.err.println("[Bootstrap] REST registration failed: " + e.getMessage());
            return;
        }

        // 2. Query the ring neighbours from the naming server
        NeighbourResponse nb;
        try {
            nb = restTemplate.getForObject(
                    namingUrl + "/api/nodes/neighbours/" + myId, NeighbourResponse.class);
        } catch (Exception e) {
            // 404 → only node in the ring; stay as prev=next=self
            System.out.println("[Bootstrap] Alone in the ring (REST).");
            return;
        }

        if (nb == null) {
            System.out.println("[Bootstrap] Alone in the ring (REST).");
            return;
        }

        // 3. Update our own state
        int prevId = nb.getPrevId();
        int nextId = nb.getNextId();
        state.setPrevId(prevId);
        state.setNextId(nextId);
        System.out.println("[Bootstrap] REST ring: prev=" + prevId + " next=" + nextId);

        // 4. Tell our new neighbours to point to us
        tellNeighbour(prevId, "next", myId);   // prev node's new next = me
        tellNeighbour(nextId, "prev", myId);   // next node's new prev = me
    }

    private void tellNeighbour(int neighbourId, String endpoint, int myId) {
        try {
            NodeInfo info = restTemplate.getForObject(
                    namingUrl + "/api/nodes/" + neighbourId, NodeInfo.class);
            if (info == null) return;
            String url = "http://" + info.getIp() + ":" + info.getPort() + "/node/" + endpoint;
            restTemplate.put(url, Map.of("id", myId));
            System.out.println("[Bootstrap] Told node " + neighbourId + ": " + endpoint + "=" + myId);
        } catch (Exception e) {
            System.err.println("[Bootstrap] Could not update neighbour " + neighbourId
                    + " (" + endpoint + "): " + e.getMessage());
        }
    }
}
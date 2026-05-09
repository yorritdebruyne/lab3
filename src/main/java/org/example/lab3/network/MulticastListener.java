package org.example.lab3.network;

import org.example.lab3.model.NodeInfo;
import org.example.lab3.service.NodeRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Listens on a UDP multicast group for bootstrap messages from new nodes.
 *
 * Message format: "name:ip:port"  e.g. "nodeA:127.0.0.1:8081"
 *
 * This component:
 *   1. Receives that packet.
 *   2. Registers the node in the NodeRegistry (storing name, ip AND port).
 *   3. Sends back a true unicast reply via a plain DatagramSocket to
 *      port (multicastPort + 1 = 4447) with the node count.
 *
 * Storing the port is essential for localhost testing where multiple nodes
 * share the same IP but use different HTTP ports.
 */
@Profile("naming-server")
@Component
public class MulticastListener {

    @Value("${multicast.group:230.0.0.0}")
    private String multicastGroup;

    @Value("${multicast.port:4446}")
    private int multicastPort;

    private final NodeRegistry registry;

    public MulticastListener(NodeRegistry registry) {
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startListening() {
        Thread thread = new Thread(this::listenLoop, "multicast-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void listenLoop() {
        try (MulticastSocket socket = new MulticastSocket(multicastPort)) {

            InetAddress group = InetAddress.getByName(multicastGroup);
            NetworkInterface networkInterface = NetworkInterface.getByIndex(0);
            socket.joinGroup(new InetSocketAddress(group, multicastPort), networkInterface);

            System.out.println("[MulticastListener] Listening on "
                    + multicastGroup + ":" + multicastPort);

            byte[] buf = new byte[256];

            while (true) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8).trim();
                System.out.println("[MulticastListener] Received: " + message
                        + " from " + packet.getAddress());

                // Skip anything that is not "name:ip" or "name:ip:port"
                String[] parts = message.split(":");
                if (parts.length < 2) continue;

                String nodeName = parts[0];
                String nodeIp   = parts[1];
                // Extract port if present (name:ip:port), otherwise use default
                int    nodePort = (parts.length >= 3) ? Integer.parseInt(parts[2]) : 8081;

                // Count existing nodes BEFORE adding the new one
                int existingCount = registry.getNodeCount();

                // Register the node — store name, ip AND port
                try {
                    NodeInfo added = registry.addNode(nodeName, nodeIp, nodePort);
                    System.out.println("[MulticastListener] Registered node: "
                            + added.getId() + " / " + nodeIp + ":" + nodePort);
                } catch (IllegalArgumentException e) {
                    System.out.println("[MulticastListener] Node already exists: " + nodeName);
                }

                // Send true unicast reply to dedicated reply port (multicastPort + 1 = 4447)
                // Uses a plain DatagramSocket so the reply does NOT loop back through
                // the multicast group.
                int replyPort = multicastPort + 1;
                byte[] replyBytes = String.valueOf(existingCount)
                        .getBytes(StandardCharsets.UTF_8);

                try (DatagramSocket unicastSocket = new DatagramSocket()) {
                    DatagramPacket response = new DatagramPacket(
                            replyBytes, replyBytes.length,
                            InetAddress.getByName(nodeIp),
                            replyPort
                    );
                    unicastSocket.send(response);
                    System.out.println("[MulticastListener] Sent count=" + existingCount
                            + " to " + nodeIp + ":" + replyPort);
                }
            }

        } catch (Exception e) {
            System.err.println("[MulticastListener] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
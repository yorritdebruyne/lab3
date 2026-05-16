package org.example.lab3.service;

import org.example.lab3.model.NeighbourResponse;
import org.example.lab3.model.NodeInfo;
import org.example.lab3.storage.NodeRegistryStorage;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.*;

@Profile("naming-server")
@Service
public class NodeRegistry {

    private final NavigableMap<Integer, NodeInfo> nodes = new TreeMap<>();
    private final HashService hashService;
    private final NodeRegistryStorage storage;

    public NodeRegistry(HashService hashService, NodeRegistryStorage storage) {
        this.hashService = hashService;
        this.storage = storage;
        // Load from disk
        storage.load().forEach(n -> nodes.put(n.getId(), n));
    }

    /**
     * Registers or updates a node (upsert).
     * If a node with the same name already exists (e.g. after a naming server
     * restart loading stale nodes.json), the old entry is replaced so that
     * the fresh httpPort and tcpPort are stored and nodes.json is rewritten.
     */
    public synchronized NodeInfo addNode(String name, String ip, int port, int tcpPort) {
        // Remove stale entry for this name so re-registration always refreshes ports
        nodes.values().removeIf(n -> n.getName().equals(name));

        int id = hashService.hashToRing(name);
        NodeInfo node = new NodeInfo(id, name, ip, port, tcpPort);

        nodes.put(id, node);
        storage.save(nodes.values());
        return node;
    }

    public synchronized NodeInfo addNode(String name, String ip, int port) {
        return addNode(name, ip, port, 9000);
    }

    public synchronized NodeInfo addNode(String name, String ip) {
        return addNode(name, ip, 8081, 9000);
    }

    public synchronized NodeInfo getNode(int id) {
        return nodes.get(id);
    }

    public synchronized void removeNode(String name) {
        Optional<Integer> key = nodes.entrySet().stream()
                .filter(e -> e.getValue().getName().equals(name))
                .map(Map.Entry::getKey)
                .findFirst();

        key.ifPresent(k -> {
            nodes.remove(k);
            storage.save(nodes.values());
        });
    }

    public synchronized NodeInfo findOwnerForFile(String filename) {
        if (nodes.isEmpty()) {
            return null;
        }
        int fileHash = hashService.hashToRing(filename);
        SortedMap<Integer, NodeInfo> head = nodes.headMap(fileHash);

        if (!head.isEmpty()) {
            return nodes.get(head.lastKey());
        } else {
            return nodes.get(nodes.lastKey());
        }
    }

    /**
     * Returns the current number of nodes in the ring.
     * Used by MulticastListener to tell a new node how many peers existed before it.
     */
    public synchronized int getNodeCount() {
        return nodes.size();
    }

    /**
     * Returns all nodes in the registry.
     * Used by GET /api/nodes (GUI lab).
     */
    public synchronized Collection<NodeInfo> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * Finds the previous and next node IDs relative to a given node ID in the ring.
     *
     * The ring wraps around: if the node is the smallest, its "previous" is the largest.
     * If the node is the largest, its "next" is the smallest.
     *
     * @param id The ring position of the node we want neighbours for.
     * @return NeighbourResponse with prevId and nextId, or null if less than 2 nodes exist.
     */
    public synchronized NeighbourResponse getNeighbours(int id) {
        if (nodes.size() < 2) return null;

        // headMap gives all keys strictly less than id → the previous node is the last of those.
        SortedMap<Integer, NodeInfo> before = nodes.headMap(id);
        // tailMap gives all keys strictly greater than id → the next node is the first of those.
        SortedMap<Integer, NodeInfo> after  = nodes.tailMap(id + 1);

        // Wrap around the ring if needed (circular structure).
        int prevId = before.isEmpty() ? nodes.lastKey()  : before.lastKey();
        int nextId = after.isEmpty()  ? nodes.firstKey() : after.firstKey();

        return new NeighbourResponse(prevId, nextId);
    }

}
